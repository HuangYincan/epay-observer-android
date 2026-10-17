package dev.epay.observer.probe

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in test driver only. Reads aggregate probe state, never notification content. */
@RunWith(AndroidJUnit4::class)
class ProbeLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val window get() = ProbeRuntime.window
    private val power get() = context.getSystemService(PowerManager::class.java)
    private val keyguard get() = context.getSystemService(KeyguardManager::class.java)
    private val component get() = ComponentName(context, AlipayListener::class.java)
    private lateinit var screen: ProbeActivity

    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        java.io.FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText() }.trim()
    }
    private fun await(label: String, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 20_000
        while (!predicate() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100)
        assertTrue(label, predicate())
    }
    private fun hold(seconds: Int) {
        repeat(seconds) { SystemClock.sleep(1000) }
    }
    private fun wake() {
        shell("input keyevent KEYCODE_WAKEUP")
        await("screen interactive") { power.isInteractive }
        if (keyguard.isKeyguardLocked) shell("wm dismiss-keyguard")
        await("non-secure keyguard fully dismissed") { !keyguard.isKeyguardLocked }
    }
    private fun lock(requireKeyguard: Boolean = true) {
        shell("input keyevent KEYCODE_SLEEP")
        await("screen off") { !power.isInteractive }
        if (requireKeyguard) await("keyguard locked") { keyguard.isKeyguardLocked }
    }
    private fun start() {
        wake()
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { screen.findViewById<Button>(R.id.probe_start).performClick() }
        assertEquals(ProbeWindow.Phase.RUNNING, window.snapshot().phase)
    }
    private fun report(name: String, extra: Map<String, Any> = emptyMap(), conditionCallbacks: Int? = null) {
        val state = window.snapshot()
        val result = JSONObject(mapOf("case" to name, "infrastructure" to "Passed",
            "connected" to state.connected, "phase" to state.phase.name, "callbacks" to state.callbacks,
            "condition_callbacks" to (conditionCallbacks ?: JSONObject.NULL),
            "notification_delivery" to when {
                conditionCallbacks == null -> "Not assessed"
                conditionCallbacks > 0 -> "Observed"
                else -> "Not exercised"
            },
            "instrumented_process" to true) + extra)
        instrumentation.sendStatus(2, Bundle().apply { putString("probe_automation_result", result.toString()) })
    }
    private fun running() {
        assertTrue("listener connected", window.snapshot().connected)
        assertEquals(ProbeWindow.Phase.RUNNING, window.snapshot().phase)
    }

    @Test fun automate() {
        val args = InstrumentationRegistry.getArguments()
        assertEquals("Explicit local automation opt-in required", "true", args.getString("probeAutomation"))
        assertTrue("Emulator only", android.os.Build.MODEL.startsWith("sdk_gphone"))
        assertFalse("Secure device lock is not bypassed", keyguard.isDeviceSecure)
        assertEquals(InstalledSource.Status.MATCH, InstalledSource.check(context))
        val scenario = args.getString("probeScenario")
        assertTrue(scenario in setOf("fresh", "locked", "screen_off", "offline", "doze", "idle", "arm_fault"))
        if (scenario == "fresh") {
            assertEquals("New process must not auto-arm", ProbeWindow.Phase.IDLE, window.snapshot().phase)
            assertEquals(0, window.snapshot().callbacks)
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        val hadAccess = manager.isNotificationListenerAccessGranted(component)
        val wasInteractive = power.isInteractive
        window.stop()
        try {
            wake()
            shell("cmd notification allow_listener ${component.flattenToString()}")
            await("listener connected") { window.snapshot().connected }
            screen = instrumentation.startActivitySync(Intent(context, ProbeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ProbeActivity
            when (scenario) {
                "fresh" -> {
                    assertEquals(ProbeWindow.Phase.IDLE, window.snapshot().phase)
                    assertEquals(0, window.snapshot().callbacks)
                    report("fresh_process_stopped")
                }
                "arm_fault" -> {
                    start(); report("armed_for_fault", mapOf("pid" to android.os.Process.myPid()))
                    hold(60)
                    fail("Host must perform the explicitly requested process fault")
                }
                "locked", "screen_off" -> repeat(3) { round ->
                    start(); lock(requireKeyguard = scenario == "locked")
                    val before = window.snapshot().callbacks
                    hold(20)
                    assertFalse("screen remained off during locked interval", power.isInteractive)
                    if (scenario == "locked") assertTrue("keyguard remained locked during interval", keyguard.isKeyguardLocked)
                    running(); report("${scenario}_${round + 1}", mapOf("held_seconds" to 20,
                        "keyguard_locked" to keyguard.isKeyguardLocked), window.snapshot().callbacks - before)
                    window.stop(); wake()
                }
                "offline" -> offline()
                "doze" -> doze()
                "idle" -> {
                    start(); lock(requireKeyguard = false)
                    val before = window.snapshot().callbacks
                    hold(135)
                    assertFalse(power.isInteractive)
                    assertTrue(window.snapshot().connected)
                    assertEquals(ProbeWindow.Phase.EXPIRED, window.snapshot().phase)
                    report("idle_window_expired", mapOf("held_seconds" to 135, "counting_limit_seconds" to 120), window.snapshot().callbacks - before)
                }
            }
        } finally {
            window.stop()
            if (::screen.isInitialized) instrumentation.runOnMainSync { screen.finish() }
            if (!hadAccess) shell("cmd notification disallow_listener ${component.flattenToString()}")
            if (wasInteractive) wake() else shell("input keyevent KEYCODE_SLEEP")
        }
    }

    private fun offline() {
        val wifi = shell("settings get global wifi_on")
        val mobile = shell("settings get global mobile_data")
        assertTrue("Known Wi-Fi state", wifi in setOf("0", "1"))
        assertTrue("Known mobile data state", mobile in setOf("0", "1"))
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.ACCESS_NETWORK_STATE")
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        try {
            assertNotNull("Baseline default network required", connectivity.activeNetwork)
            start()
            var offlineCallbacks = 0
            try {
                shell("svc wifi disable"); shell("svc data disable")
                await("default network offline") { connectivity.activeNetwork == null }
                val before = window.snapshot().callbacks
                hold(15); running()
                offlineCallbacks = window.snapshot().callbacks - before
            } finally {
                shell("svc wifi ${if (wifi == "1") "enable" else "disable"}")
                shell("svc data ${if (mobile == "1") "enable" else "disable"}")
            }
            await("default network restored") { connectivity.activeNetwork != null }
            running(); report("network_offline_restore", mapOf("offline_seconds" to 15), offlineCallbacks)
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    private fun doze() {
        assertFalse("Existing forced idle not modified", power.isDeviceIdleMode)
        assertFalse("Existing battery simulation not modified", shell("dumpsys battery").contains("UPDATES STOPPED"))
        start()
        try {
            shell("dumpsys battery unplug"); lock(requireKeyguard = false)
            shell("dumpsys deviceidle force-idle")
            await("device entered forced Doze") { power.isDeviceIdleMode }
            val before = window.snapshot().callbacks
            hold(15); running()
            assertTrue("forced Doze persisted", power.isDeviceIdleMode)
            report("forced_doze", mapOf("held_seconds" to 15, "forced" to true), window.snapshot().callbacks - before)
        } finally {
            shell("dumpsys deviceidle unforce")
            shell("dumpsys battery reset")
        }
    }
}
