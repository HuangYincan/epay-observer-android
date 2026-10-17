package dev.epay.observer.probe

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** User sends one ordinary message after armed; only probe aggregates leave this test. */
@RunWith(AndroidJUnit4::class)
class ProbeLiveDeliveryTest {
    @Test fun observeUserTriggeredMessage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        assertEquals("Explicit local opt-in required", "true", args.getString("probeUserMessage"))
        assertTrue("Emulator only", android.os.Build.MODEL.startsWith("sdk_gphone"))
        assertEquals(InstalledSource.Status.MATCH, InstalledSource.check(context))
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val component = ComponentName(context, AlipayListener::class.java)
        assertTrue(context.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(component))
        assertTrue("Screen already awake", power.isInteractive)
        assertFalse("This is an unlocked screen-on condition", keyguard.isKeyguardLocked)
        val window = ProbeRuntime.window
        assertNotEquals("Do not replace an existing window", ProbeWindow.Phase.RUNNING, window.snapshot().phase)
        var screen: ProbeActivity? = null
        var began = 0L
        fun emit(event: String) {
            val state = window.snapshot()
            instrumentation.sendStatus(2, Bundle().apply {
                putString("probe_live_state", JSONObject(mapOf(
                    "event" to event, "phase" to state.phase.name, "callbacks" to state.callbacks,
                    "remaining_seconds" to state.remainingSeconds, "connected" to state.connected,
                    "source_matches" to (InstalledSource.check(context) == InstalledSource.Status.MATCH),
                    "elapsed_ms" to (SystemClock.elapsedRealtime() - began),
                    "screen_interactive" to power.isInteractive, "keyguard_locked" to keyguard.isKeyguardLocked,
                    "instrumented_process" to true
                )).toString())
            })
        }
        try {
            val connectedBy = SystemClock.elapsedRealtime() + 20_000
            while (!window.snapshot().connected && SystemClock.elapsedRealtime() < connectedBy) SystemClock.sleep(100)
            assertTrue("Existing listener connected", window.snapshot().connected)
            val activity = instrumentation.startActivitySync(Intent(context, ProbeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ProbeActivity
            screen = activity
            instrumentation.runOnMainSync {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                assertTrue(activity.findViewById<Button>(R.id.probe_start).performClick())
            }
            began = SystemClock.elapsedRealtime()
            assertEquals(ProbeWindow.Phase.RUNNING, window.snapshot().phase)
            assertEquals("No callback before user prompt", 0, window.snapshot().callbacks)
            emit("armed")
            var lastCallbacks = 0
            var sample = 0
            while (window.snapshot().phase == ProbeWindow.Phase.RUNNING) {
                SystemClock.sleep(1000)
                assertTrue("Listener remained connected", window.snapshot().connected)
                assertEquals(InstalledSource.Status.MATCH, InstalledSource.check(context))
                assertTrue("Screen remained interactive", power.isInteractive)
                assertFalse("Keyguard remained unlocked", keyguard.isKeyguardLocked)
                val callbacks = window.snapshot().callbacks
                sample++
                if (callbacks != lastCallbacks || sample % 10 == 0) emit("sample")
                lastCallbacks = callbacks
            }
            assertEquals("Full privacy window ended normally", ProbeWindow.Phase.EXPIRED, window.snapshot().phase)
            emit("finished")
        } finally {
            window.stop()
            screen?.let { activity -> instrumentation.runOnMainSync {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity.finish()
            } }
        }
    }
}
