package dev.epay.observer

import android.content.ComponentName
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.epay.observer.capture.CaptureState
import dev.epay.observer.capture.ObserverListener
import dev.epay.observer.parsing.Provider
import dev.epay.observer.protocol.HealthCheck
import dev.epay.observer.protocol.HealthState
import dev.epay.observer.protocol.UploadRuntime
import dev.epay.observer.security.PairingCoordinator
import dev.epay.observer.security.PairingRuntime
import dev.epay.observer.storage.DurableCapture
import dev.epay.observer.storage.ObserverDatabase
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RechargeNetworkTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        java.io.FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText() }
    }
    private fun post(scenario: String) {
        assertTrue(shell("am start -W -f 0x20000000 -n dev.epay.observer.fixture/.FixtureActivity --es scenario $scenario").contains("Status: ok"))
    }
    private fun await(description: String, timeout: Long = 15000, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100)
        assertTrue(description, condition())
    }
    @Test fun independentNotificationsForOrdinaryRechargeOrders() {
        PairingRuntime.awaitIdle(); instrumentation.waitForIdleSync()
        val bootstrap = File(context.noBackupFilesDir, "pairing-network-bootstrap.json")
        val bytes = bootstrap.readBytes(); check(bootstrap.delete())
        val grants = try { JSONArray(String(bytes)) } finally { bytes.fill(0) }
        val db = ObserverDatabase.open(context)
        val coordinator = PairingCoordinator(context, db)
        val component = ComponentName(context, ObserverListener::class.java).flattenToString()
        try {
            assertEquals("explicit dedicated client reset required", 0, db.observerDao().eventCount())
            shell("pm grant dev.epay.observer.fixture android.permission.POST_NOTIFICATIONS")
            post("clear")
            shell("cmd notification allow_listener $component")
            await("listener connected") { CaptureState.connected }
            post("identical-pair")
            await("historical UNBOUND") { db.observerDao().eventCount() == 2 }
            assertTrue(db.observerDao().events().all { it.state == "UNBOUND" })
            for (i in 0 until grants.length()) {
                val grant = grants.getJSONObject(i)
                coordinator.confirm(coordinator.inspect(grant.getJSONObject("scope").getString("origin"), grant.getString("token")))
            }
            PairingRuntime.recover(); PairingRuntime.awaitIdle(); instrumentation.waitForIdleSync()
            assertEquals(Provider.entries.toSet(), PairingRuntime.active.keys)
            for (provider in Provider.entries) assertEquals(HealthState.AUTHORIZED, HealthCheck(context, db).check(provider))
            assertEquals(0, db.outboxDao().count())
            File(context.filesDir, "recharge-paired").writeText("ready")
            // Host creates ordinary New API orders through the normal APIs.
            // Only a phase marker crosses this boundary; no order ID or amount.
            val ready = File(context.filesDir, "recharge-orders-ready")
            await("ordinary orders ready", 120000) { ready.isFile }
            check(ready.delete())
            post("wxpay"); post("alipay")
            await("two notifications queued") { db.outboxDao().count() == 2 }
            await("two durable HTTPS receipts", 90000) {
                db.outboxDao().counts().any { it.state == "ACKED" && it.count == 2 }
            }
            UploadRuntime.awaitIdle(); DurableCapture.awaitIdle()
            val rows = db.outboxDao().rows()
            assertEquals(setOf("wxpay", "alipay"), rows.map { it.provider }.toSet())
            assertEquals(2, db.observerDao().events().count { it.state == "UNBOUND" })
            rows.forEach {
                val body = JSONObject(String(it.body))
                assertFalse(body.has("order_id")); assertFalse(body.has("trade_no")); assertFalse(body.has("provider_transaction_id"))
                assertEquals(1000, body.getInt("amount_minor"))
            }
            val report = JSONObject().put("layer", "emulator independent synthetic notifications")
                .put("providers", JSONArray(listOf("wxpay", "alipay"))).put("historical_unbound", 2)
                .put("acked_events", 2).put("transport_attempts", rows.sumOf { it.attempts })
                .put("notification_order_hints", false).put("balance_verified_by_android", false)
            File(context.filesDir, "avd-recharge-result.json").writeText(report.toString(2))
        } finally {
            post("clear"); shell("cmd notification disallow_listener $component")
            db.close()
        }
    }
    @Test fun existingPairingDeliversAnotherIndependentNotification() {
        val scenario = InstrumentationRegistry.getArguments().getString("scenario")!!
        require(scenario in setOf("fresh-wxpay", "fresh-alipay", "fresh-wxpay-20", "fresh-alipay-20", "fresh-wxpay-40"))
        PairingRuntime.recover(); PairingRuntime.awaitIdle(); instrumentation.waitForIdleSync()
        assertEquals(Provider.entries.toSet(), PairingRuntime.active.keys)
        val component = ComponentName(context, ObserverListener::class.java).flattenToString()
        val db = ObserverDatabase.open(context)
        try {
            val before = db.outboxDao().rows().map { it.eventId }.toSet()
            shell("cmd notification allow_listener $component")
            await("listener connected") { CaptureState.connected }
            post(scenario)
            await("new independent observation queued") { db.outboxDao().count() == before.size + 1 }
            await("new receipt durably ACKED", 90000) { db.outboxDao().rows().any { it.eventId !in before && it.state == "ACKED" } }
            val body = JSONObject(String(db.outboxDao().rows().single { it.eventId !in before }.body))
            assertEquals("distinct", body.getString("identity_confidence"))
            assertFalse(body.has("trade_no")); assertFalse(body.has("order_id")); assertFalse(body.has("provider_transaction_id"))
        } finally {
            shell("cmd notification disallow_listener $component")
            db.close()
        }
    }
}
