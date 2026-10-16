package dev.epay.observer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.NetworkType
import androidx.work.WorkManager
import dev.epay.observer.parsing.Provider
import dev.epay.observer.protocol.*
import dev.epay.observer.security.HmacKeyVault
import dev.epay.observer.storage.*
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackgroundInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(count: Int, block: (ObserverDatabase, ActiveCredential) -> Unit) {
        val name = "background-${UUID.randomUUID()}.db"
        val db = ObserverDatabase.open(context, name)
        val binding = syntheticBinding().copy(deviceId = "background-test-device")
        val key = "test-${UUID.randomUUID()}"
        try {
            HmacKeyVault(context).install(binding, key, vaultMaterial())
            PairingStore(db).activate(PairingEnrollment(UUID.randomUUID().toString(), binding, key, byteArrayOf(), 0, 1_000_000))
            val active = PairingStore(db).active(Provider.WXPAY)!!
            repeat(count) { n ->
                IdentityStore(db).record(identityInput("background-$n"), Provider.entries.toSet(),
                    credentialsAtCapture = mapOf(Provider.WXPAY to active))
            }
            block(db, active)
        } finally { HmacKeyVault(context).remove(binding, key); db.close(); context.deleteDatabase(name) }
    }
    private fun ack(request: SignedRequest) = HttpResult(202, JSONObject().put("schema_version", 1)
        .put("event_id", JSONObject(String(request.bodyBytes())).getString("event_id"))
        .put("receipt_status", "accepted").put("processing_status", "pending")
        .put("received_at_ms", 2000).toString().toByteArray(), true)

    @Test fun batchLimitAndStopLeaveDurableRemainder() = fixture(25) { db, _ ->
        var sends = 0
        val client = EventClient { _, request -> sends++; ack(request) }
        val drain = UploadDrain(db, OutboxDelivery(context, db, client, { 2000 }))
        assertNotNull(drain.run())
        assertEquals(20, sends)
        assertEquals(5, db.outboxDao().rows().count { it.state == "READY" })
        assertNotNull(drain.run { true })
        assertEquals(20, sends)
        assertNull(drain.run())
        assertEquals(25, sends)
        assertTrue(db.outboxDao().rows().all { it.state == "ACKED" && it.attempts == 1L })
    }
    @Test fun abandonedLeaseAndRetryUsePersistedDueTimeWithoutClearingHolds() = fixture(1) { db, active ->
        val old = PairingStore(db).claimUpload(active, 2000)!!
        val body = old.body.copyOf()
        var now = 2001L
        var sends = 0
        val client = EventClient { _, request ->
            sends++; assertArrayEquals(body, request.bodyBytes())
            if (sends == 1) throw java.io.IOException("synthetic outage")
            ack(request)
        }
        val drain = UploadDrain(db, OutboxDelivery(context, db, client, { now }, RetryPolicy { 1000 }))
        assertEquals(old.until, drain.run())
        assertEquals(0, sends)
        now = old.until
        assertEquals(now + 1000, drain.run())
        assertEquals(1, sends)
        assertFalse(OutboxStore(db).hold(old, HoldReason.CONFLICT))
        assertTrue(PairingStore(db).pauseUploads(active, HoldReason.AUTHENTICATION))
        now += 1000
        assertNull(drain.run())
        assertEquals(1, sends)
        PairingStore(db).resumeUploads(Provider.WXPAY)
        assertNull(drain.run())
        assertEquals(2, sends)
        assertEquals("ACKED", db.outboxDao().rows().single().state)
    }
    @Test fun elapsedBudgetLeavesRemainingEventsForNextPass() = fixture(2) { db, _ ->
        var elapsed = 0L
        val drain = UploadDrain(db, OutboxDelivery(context, db, EventClient { _, request ->
            elapsed = 120_000; ack(request)
        }, { 2000 }), { elapsed })
        assertNotNull(drain.run())
        assertEquals(1, db.outboxDao().rows().count { it.state == "ACKED" })
        assertEquals(1, db.outboxDao().rows().count { it.state == "READY" })
    }
    @Test fun realWorkManagerPersistsReconciliationAndReplacesOnlyWakeHints() {
        val manager = WorkManager.getInstance(context)
        UploadScheduler.reconcile(context).result.get()
        UploadScheduler.reconcile(context).result.get()
        val periodic = manager.getWorkInfosForUniqueWork(UploadScheduler.RECONCILE).get().filter { !it.state.isFinished }
        assertEquals(1, periodic.size)
        assertEquals(NetworkType.CONNECTED, periodic.single().constraints.requiredNetworkType)
        assertEquals(900_000L, periodic.single().periodicityInfo!!.repeatIntervalMillis)
        val now = System.currentTimeMillis()
        try {
            UploadScheduler.wake(context, now + 120_000, now).result.get()
            UploadScheduler.wake(context, now + 60_000, now).result.get()
            val wakes = manager.getWorkInfosForUniqueWork(UploadScheduler.WAKE).get().filter { !it.state.isFinished }
            assertEquals(1, wakes.size)
            assertEquals(60_000L, wakes.single().initialDelayMillis)
            assertEquals(UploadWakeWorker::class.java.name, wakes.single().workerClassName)
        } finally { manager.cancelUniqueWork(UploadScheduler.WAKE).result.get() }
    }
}
