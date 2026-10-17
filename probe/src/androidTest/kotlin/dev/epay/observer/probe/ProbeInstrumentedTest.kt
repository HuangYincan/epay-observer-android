package dev.epay.observer.probe

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in local AVD control; does not drive Alipay, enumerate or read notification content. */
@RunWith(AndroidJUnit4::class)
class ProbeInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val component = ComponentName(context, AlipayListener::class.java)
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        java.io.FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText() }
    }
    private fun await(label: String, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15_000
        while (!predicate() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100)
        assertTrue(label, predicate())
    }

    @Test fun installedSourceListenerAndManualWindow() {
        assertEquals("Explicit local opt-in required", "true", InstrumentationRegistry.getArguments().getString("probeLocalAvd"))
        assertTrue("Emulator only", android.os.Build.MODEL.startsWith("sdk_gphone"))
        assertEquals(InstalledSource.Status.MATCH, InstalledSource.check(context))
        assertFalse(ProbeRuntime.window.snapshot().phase == ProbeWindow.Phase.RUNNING)
        val access = context.getSystemService(NotificationManager::class.java)
        val originallyAllowed = access.isNotificationListenerAccessGranted(component)
        var activity: ProbeActivity? = null
        try {
            shell("cmd notification allow_listener ${component.flattenToString()}")
            await("listener connected") { ProbeRuntime.window.snapshot().connected }
            assertTrue(access.isNotificationListenerAccessGranted(component))
            activity = instrumentation.startActivitySync(Intent(context, ProbeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ProbeActivity
            val screen = activity
            instrumentation.runOnMainSync {
                assertTrue(screen.findViewById<TextView>(R.id.probe_status).text.toString().contains("当前版本和签名匹配"))
                assertTrue(screen.findViewById<Button>(R.id.probe_start).performClick())
            }
            assertEquals(ProbeWindow.Phase.RUNNING, ProbeRuntime.window.snapshot().phase)
            // No synthetic source is allowed to impersonate Alipay in this test.
            instrumentation.runOnMainSync { screen.findViewById<Button>(R.id.probe_stop).performClick() }
            assertEquals(ProbeWindow.Phase.STOPPED, ProbeRuntime.window.snapshot().phase)
            instrumentation.runOnMainSync { screen.findViewById<Button>(R.id.probe_start).performClick() }
            shell("cmd notification disallow_listener ${component.flattenToString()}")
            await("listener disconnected") { !ProbeRuntime.window.snapshot().connected }
            assertEquals(ProbeWindow.Phase.DISCONNECTED, ProbeRuntime.window.snapshot().phase)
            shell("cmd notification allow_listener ${component.flattenToString()}")
            await("listener reconnected") { ProbeRuntime.window.snapshot().connected }
            assertNotEquals(ProbeWindow.Phase.RUNNING, ProbeRuntime.window.snapshot().phase)
        } finally {
            ProbeRuntime.window.stop()
            if (!originallyAllowed) shell("cmd notification disallow_listener ${component.flattenToString()}")
            activity?.let { screen -> instrumentation.runOnMainSync { screen.finish() } }
        }
    }
}
