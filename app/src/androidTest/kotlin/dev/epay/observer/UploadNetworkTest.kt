package dev.epay.observer

import android.content.ComponentName
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.epay.observer.capture.CaptureState
import dev.epay.observer.capture.ObserverListener
import dev.epay.observer.parsing.Provider
import dev.epay.observer.protocol.UploadRuntime
import dev.epay.observer.security.*
import dev.epay.observer.storage.*
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UploadNetworkTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        java.io.FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText() }
    }
    private fun post(scenario: String) {
        assertTrue(shell("am start -W -f 0x20000000 -n dev.epay.observer.fixture/.FixtureActivity --es scenario $scenario").contains("Status: ok"))
    }
    private fun await(description: String, timeout: Long = 12000, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100)
        assertTrue(description, condition())
    }
    @Test fun independentNotificationsReachDurableGatewayAndRecoverMissingAck() {
        PairingRuntime.awaitIdle(); instrumentation.waitForIdleSync()
        val file = File(context.noBackupFilesDir, "pairing-network-bootstrap.json")
        val bytes = file.readBytes(); check(file.delete())
        val grants = try { JSONArray(String(bytes)) } finally { bytes.fill(0) }
        val db = ObserverDatabase.open(context)
        val coordinator = PairingCoordinator(context, db)
        val component = ComponentName(context, ObserverListener::class.java).flattenToString()
        try {
            assertEquals("explicit test-client reset required", 0, db.observerDao().eventCount())
            verifyPairingTlsBoundary(coordinator)
            shell("pm grant dev.epay.observer.fixture android.permission.POST_NOTIFICATIONS")
            post("clear")
            shell("cmd notification allow_listener $component")
            await("listener connected") { CaptureState.connected }
            // Two separate notification keys with equal content, before pairing.
            post("identical-pair")
            await("historical unbound persisted") { db.observerDao().eventCount() == 2 }
            assertTrue(db.observerDao().events().all { it.state == "UNBOUND" })
            assertTrue(db.outboxDao().rows().isEmpty())
            for (i in 0 until grants.length()) {
                val grant = grants.getJSONObject(i)
                val origin = grant.getJSONObject("scope").getString("origin")
                coordinator.confirm(coordinator.inspect(origin, grant.getString("token")))
            }
            PairingRuntime.recover(); PairingRuntime.awaitIdle(); instrumentation.waitForIdleSync()
            assertEquals(Provider.entries.toSet(), PairingRuntime.active.keys)
            post("wxpay"); post("alipay")
            await("both actual notifications queued") { db.outboxDao().count() == 2 }
            // The bridge commits the first event but drops its ACK body. This
            // WorkManager must recover using the persisted due time without a
            // polling loop that triggers uploads from the test.
            await("durable HTTPS ACK recovery", 60000) {
                db.outboxDao().counts().any { it.state == "ACKED" && it.count == 2 }
            }
            UploadRuntime.awaitIdle(); DurableCapture.awaitIdle()
            val rows = db.outboxDao().rows()
            assertEquals(3L, rows.sumOf { it.attempts })
            assertEquals(setOf("wxpay", "alipay"), rows.map { it.provider }.toSet())
            assertEquals(2, db.observerDao().events().count { it.state == "UNBOUND" })
            rows.forEach {
                val body = JSONObject(String(it.body))
                assertFalse(body.has("order_id")); assertFalse(body.has("trade_no")); assertFalse(body.has("provider_transaction_id"))
            }
            val report = JSONObject().put("layer", "emulator independent synthetic notifications over signed HTTPS")
                .put("providers", JSONArray(listOf("wxpay", "alipay"))).put("historical_unbound", 2).put("acked_events", 2)
                .put("transport_attempts", 3).put("missing_ack_recovered", true).put("payment_verified", false)
                .put("unknown_ca_rejected", true).put("redirect_rejected", true).put("unexpected_origin_rejected", true)
            File(context.filesDir, "avd-upload-result.json").writeText(report.toString(2))
        } finally {
            post("clear"); shell("cmd notification disallow_listener $component")
            // Leave this dedicated debug client's audit DB for local inspection.
            // Reproduction starts with the script's explicit client-only reset.
            db.close()
        }
    }
}
