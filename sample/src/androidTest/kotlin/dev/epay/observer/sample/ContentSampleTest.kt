package dev.epay.observer.sample

import android.app.NotificationManager
import android.content.ComponentName
import android.os.SystemClock
import android.os.Bundle
import android.service.notification.NotificationListenerService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ContentSampleTest {
    @Test fun prepareOnlyRetry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("unpaid-scan-retry-prepare-only", InstrumentationRegistry.getArguments().getString("contentSampleConsent"))
        assertTrue(android.os.Build.MODEL.startsWith("sdk_gphone"))
        assertEquals(InstalledSource.Status.MATCH, InstalledSource.check(context))
        assertTrue(File(context.noBackupFilesDir, "used").exists())
        assertTrue(File(context.noBackupFilesDir, "used-scan-payment-v1").exists())
        assertFalse(File(context.noBackupFilesDir, "used-scan-payment-unpaid-retry-v1").exists())
        assertFalse(File(context.noBackupFilesDir, "samples.jsonl").exists())
        assertNotEquals(SampleGate.Phase.RUNNING, SampleRuntime.gate(context).snapshot().phase)
        assertFalse(context.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(context, AlipayListener::class.java)))
    }

    @Test fun prepareOnly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("scan-payment-prepare-only", InstrumentationRegistry.getArguments().getString("contentSampleConsent"))
        assertTrue(android.os.Build.MODEL.startsWith("sdk_gphone"))
        assertEquals(InstalledSource.Status.MATCH, InstalledSource.check(context))
        assertTrue("Prior used marker retained", File(context.noBackupFilesDir, "used").exists())
        assertFalse("No new window consumed", File(context.noBackupFilesDir, "used-scan-payment-v1").exists())
        assertFalse("No raw data pending", File(context.noBackupFilesDir, "samples.jsonl").exists())
        assertNotEquals(SampleGate.Phase.RUNNING, SampleRuntime.gate(context).snapshot().phase)
        assertFalse(context.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(context, AlipayListener::class.java)))
    }

    @Test fun sampleOnce() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val consent = InstrumentationRegistry.getArguments().getString("contentSampleConsent")
        assertTrue(consent in setOf("local-once-120s", "scan-payment-once-120s-v1", "scan-payment-unpaid-retry-once-120s-v1"))
        assertTrue("Emulator only", android.os.Build.MODEL.startsWith("sdk_gphone"))
        assertEquals(InstalledSource.Status.MATCH, InstalledSource.check(context))
        assertTrue(context.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(context, AlipayListener::class.java)))
        if (consent == "scan-payment-once-120s-v1") instrumentation.runOnMainSync {
            SampleRuntime.prepareScanPaymentWindow(context)
        }
        if (consent == "scan-payment-unpaid-retry-once-120s-v1") instrumentation.runOnMainSync {
            SampleRuntime.prepareUnpaidScanRetryWindow(context)
        }
        val gate = SampleRuntime.gate(context)
        NotificationListenerService.requestRebind(ComponentName(context, AlipayListener::class.java))
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (!gate.snapshot().connected && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue("Listener must already be connected", gate.snapshot().connected)
        val began = System.currentTimeMillis()
        fun emit(event: String) {
            val state = gate.snapshot()
            instrumentation.sendStatus(2, Bundle().apply { putString("content_sample_state", JSONObject(mapOf(
                "event" to event, "phase" to state.phase.name, "callbacks" to state.count,
                "elapsed_ms" to state.elapsedMs,
                "remaining_seconds" to if (state.phase == SampleGate.Phase.RUNNING)
                    ((120000 - state.elapsedMs).coerceAtLeast(0) / 1000).toInt() else 0,
                "connected" to state.connected,
                "source_matches" to (InstalledSource.check(context) == InstalledSource.Status.MATCH),
                "instrumented_process" to true
            )).toString()) })
        }
        try {
            assertTrue("Single-use marker must be unused", gate.start(true))
            emit("armed")
            var lastEmit = SystemClock.elapsedRealtime()
            while (gate.snapshot().phase == SampleGate.Phase.RUNNING) {
                SystemClock.sleep(250)
                if (InstalledSource.check(context) != InstalledSource.Status.MATCH)
                    gate.posted(false, { null }, { null })
                if (SystemClock.elapsedRealtime() - lastEmit >= 1000) {
                    emit("sample"); lastEmit = SystemClock.elapsedRealtime()
                }
            }
            assertTrue("Window ended normally or at capacity", gate.snapshot().phase in
                setOf(SampleGate.Phase.EXPIRED, SampleGate.Phase.LIMIT))
        } finally {
            gate.stop()
            emit("finished")
            val state = gate.snapshot()
            File(context.noBackupFilesDir, "window.json").writeText(JSONObject(mapOf(
                "phase" to state.phase.name, "samples" to state.count,
                "elapsed_ms" to state.elapsedMs, "started_epoch_ms" to began,
                "ended_epoch_ms" to System.currentTimeMillis(), "instrumented_process" to true
            )).toString())
        }
    }
}
