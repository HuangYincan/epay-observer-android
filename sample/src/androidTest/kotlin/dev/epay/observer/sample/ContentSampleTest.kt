package dev.epay.observer.sample

import android.app.NotificationManager
import android.content.ComponentName
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ContentSampleTest {
    @Test fun sampleOnce() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("local-once-120s", InstrumentationRegistry.getArguments().getString("contentSampleConsent"))
        assertTrue("Emulator only", android.os.Build.MODEL.startsWith("sdk_gphone"))
        assertEquals(InstalledSource.Status.MATCH, InstalledSource.check(context))
        assertTrue(context.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(context, AlipayListener::class.java)))
        val gate = SampleRuntime.gate(context)
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (!gate.snapshot().connected && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue("Listener must already be connected", gate.snapshot().connected)
        val began = System.currentTimeMillis()
        try {
            assertTrue("Single-use marker must be unused", gate.start(true))
            while (gate.snapshot().phase == SampleGate.Phase.RUNNING) {
                SystemClock.sleep(250)
                if (InstalledSource.check(context) != InstalledSource.Status.MATCH)
                    gate.posted(false, { null }, { null })
            }
            assertTrue("Window ended normally or at capacity", gate.snapshot().phase in
                setOf(SampleGate.Phase.EXPIRED, SampleGate.Phase.LIMIT))
        } finally {
            gate.stop()
            val state = gate.snapshot()
            File(context.noBackupFilesDir, "window.json").writeText(JSONObject(mapOf(
                "phase" to state.phase.name, "samples" to state.count,
                "elapsed_ms" to state.elapsedMs, "started_epoch_ms" to began,
                "ended_epoch_ms" to System.currentTimeMillis(), "instrumented_process" to true
            )).toString())
        }
    }
}
