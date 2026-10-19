package dev.epay.observer

import android.app.NotificationManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.epay.observer.capture.CaptureState
import dev.epay.observer.parsing.Provider
import dev.epay.observer.pilot.*
import dev.epay.observer.security.PairingRuntime
import dev.epay.observer.storage.DurableCapture
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Trusted local controller, absent from installed Release. Never generates review approvals. */
class RealPaymentPilotTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val input get() = File(context.noBackupFilesDir, "notification-pilot-input.json")
    private val marker get() = File(context.noBackupFilesDir, "used-notification-pilot-v1")
    private fun optIn(value: String) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("observerRealPaymentPilot") == value)
        check(PilotBuild.allowed && context.packageName == "dev.epay.observer.debug")
    }
    private fun configuration(): PilotConfiguration {
        check(input.length() in 1..4096)
        return PilotConfigurationCodec.decode(input.readBytes())
    }
    private fun currentBindingMatches(c: PilotConfiguration): Boolean {
        val credential = PairingRuntime.active[Provider.ALIPAY] ?: return false
        return credential.binding == c.binding && credential.notBefore <= System.currentTimeMillis() &&
            credential.expiresAt >= c.serverExpiresAt
    }
    private fun sourceMatches(): Boolean = try {
        @Suppress("DEPRECATION")
        val p = context.packageManager.getPackageInfo(PilotAlipaySource.PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
        val digests = p.signingInfo?.apkContentsSigners?.map {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) }
        }?.toSet().orEmpty()
        p.longVersionCode == PilotAlipaySource.VERSION && digests == setOf(PilotAlipaySource.CERTIFICATE)
    } catch (_: Exception) { false }
    private fun publish(report: JSONObject) {
        val file = File(context.noBackupFilesDir, "notification-pilot-result.json")
        val temporary = File(context.noBackupFilesDir, "notification-pilot-result.tmp")
        temporary.outputStream().use { it.write(report.toString().toByteArray()) }
        check(temporary.renameTo(file))
        instrumentation.sendStatus(0, Bundle().apply { putString("notification_pilot_result",report.toString()) })
    }

    @Test fun prepareOnly() {
        optIn("prepare-only-v1")
        PairingRuntime.awaitIdle()
        val c = runCatching { configuration() }.getOrNull()
        val source = sourceMatches()
        val bound = c != null && currentBindingMatches(c)
        val access = context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(
            ComponentName(context.packageName,"dev.epay.observer.capture.ObserverListener"))
        val ready = c != null && bound && source && access && CaptureState.connected && !marker.exists() &&
            runCatching { NotificationPilot(c,System.currentTimeMillis(),SystemClock.elapsedRealtime()) }.isSuccess
        publish(JSONObject().put("phase","PREPARED").put("ready",ready).put("input_present",input.isFile)
            .put("review_ready",c != null).put("source_matches",source).put("marker_used",marker.exists())
            .put("binding_matches",bound).put("listener_granted",access).put("listener_connected",CaptureState.connected)
            .put("capture_started",false).put("payment_verified",false))
        check(!PilotRuntime.hasSession) // Preparation must never arm or consume the marker.
    }

    @Test fun runReviewedOnce() {
        optIn("run-reviewed-once-v1")
        var started = false
        var completed = false
        var cleanup = true
        try {
            PairingRuntime.awaitIdle()
            val c = configuration()
            check(currentBindingMatches(c) && sourceMatches())
            check(context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(
                ComponentName(context.packageName,"dev.epay.observer.capture.ObserverListener")))
            check(CaptureState.connected && !PilotRuntime.hasSession)
            // Validate before consuming, then consume before any content access.
            NotificationPilot(c,System.currentTimeMillis(),SystemClock.elapsedRealtime())
            check(marker.createNewFile())
            check(input.delete())
            PilotRuntime.arm(c,System.currentTimeMillis(),SystemClock.elapsedRealtime())
            started = true
            while (PilotRuntime.running(System.currentTimeMillis(),SystemClock.elapsedRealtime())) {
                // Installed source identity is rechecked at every actual callback before extras.
                check(currentBindingMatches(c))
                Thread.sleep(500)
            }
            completed = true
        } catch (_: Exception) {
            // Only stable aggregates below; no exception data, title/text or account identifiers.
        } finally {
            PilotRuntime.stop()
            try { DurableCapture.awaitIdle() } catch (_: Exception) { cleanup = false }
            CaptureState.journal.clear()
            if (input.exists() && !input.delete()) cleanup = false
            publish(JSONObject().put("phase","FINISHED").put("started",started).put("completed",completed)
                .put("source_slots",PilotRuntime.readCount()).put("field_reads",PilotRuntime.fieldReadCount()).put("cleanup_ok",cleanup)
                .put("instrumented_process",true).put("payment_verified",false))
        }
        assertTrue("notification_pilot_failed",started && completed && cleanup)
    }
}
