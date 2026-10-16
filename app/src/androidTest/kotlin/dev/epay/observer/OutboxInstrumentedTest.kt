package dev.epay.observer

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.epay.observer.parsing.*
import dev.epay.observer.storage.*
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

internal fun syntheticBinding(provider: Provider = Provider.WXPAY) = BindingSnapshot(
    "https://observer.example", "isolated-test", "device-test", "receiver-test", 1, provider, BindingSnapshot.SourceClass.SYNTHETIC,
)
internal fun queueInput(index: Int) = identityInput("queue-$index").let { input ->
    if (index % 2 == 0) input else input.copy(fields = input.fields.copy(
        title = "支付宝形态 · 合成到账", text = "到账 10.00 元", bigText = "到账 10.00 元"))
}
internal fun validAck(lease: DeliveryLease) = ReceiptAck(1, lease.eventId, 202, "accepted", "pending", 2000)

@RunWith(AndroidJUnit4::class)
class OutboxInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val enabled = Provider.entries.toSet()
    private val binding = syntheticBinding()
    @get:Rule val migration = MigrationTestHelper(instrumentation, ObserverDatabase::class.java)
    private fun withDatabase(block: (ObserverDatabase, String) -> Unit) {
        val name = "test-outbox-${UUID.randomUUID()}.db"
        val db = ObserverDatabase.open(context, name)
        try { block(db, name) } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun freshSyntheticIdentityQualifiesWithoutRewritingFrozenHistory() = withDatabase { db, _ ->
        val store = IdentityStore(db)
        val queue = OutboxStore(db)
        Provider.entries.forEach { queue.activate(syntheticBinding(it)) }
        repeat(2) { i -> store.record(queueInput(i), enabled, syntheticBinding(if (i == 0) Provider.WXPAY else Provider.ALIPAY)) }
        val original = queue.rows().associate { it.eventId to it.body }
        assertEquals(2, original.size)
        queue.rows().forEach { assertEquals("distinct", org.json.JSONObject(String(it.body)).getString("identity_confidence")) }
        store.recordGap()
        store.record(queueInput(0), enabled, binding)
        original.forEach { (id, body) -> assertArrayEquals(body, queue.rows().single { it.eventId == id }.body) }
        store.record(queueInput(2), enabled, binding)
        assertEquals("uncertain", org.json.JSONObject(String(queue.rows().single { it.eventId !in original }.body)).getString("identity_confidence"))
        store.record(identityInput("unknown-restored", restored = true), enabled, binding)
        assertEquals(3, queue.rows().size)
    }
    @Test fun fiftyOfflineDualProviderEventsRemainByteIdenticalAfterReopen() = withDatabase { db, name ->
        val store = IdentityStore(db)
        val queue = OutboxStore(db)
        Provider.entries.forEach { queue.activate(syntheticBinding(it)) }
        repeat(50) { i -> store.record(queueInput(i), enabled, syntheticBinding(if (i % 2 == 0) Provider.WXPAY else Provider.ALIPAY)) }
        val originals = queue.rows().associate { it.eventId to it.body }
        assertEquals(50, originals.size)
        assertEquals(setOf("wxpay", "alipay"), queue.rows().map { it.provider }.toSet())
        db.close()
        val reopened = ObserverDatabase.open(context, name)
        try {
            val restored = OutboxStore(reopened).rows()
            assertEquals(50, restored.size)
            restored.forEach { assertArrayEquals(originals[it.eventId], it.body); assertEquals("READY", it.state) }
            assertTrue(IdentityStore(reopened).events().all { it.observedAt == 1000L })
        } finally { reopened.close() }
    }
    @Test fun concurrentClaimRecoveryStaleResponsesAndAckSemantics() = withDatabase { db, name ->
        val queue = OutboxStore(db)
        queue.activate(binding)
        IdentityStore(db).record(identityInput(), enabled, binding)
        val second = ObserverDatabase.open(context, name)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val queues = listOf(queue, OutboxStore(second))
            val leases = (0 until 12).map { i -> executor.submit<DeliveryLease?> { queues[i % 2].claim(binding, 2000, 100) } }
                .mapNotNull { it.get(20, TimeUnit.SECONDS) }
            assertEquals(1, leases.size)
            val first = leases.single()
            assertNull(queue.claim(binding, 2099))
            val recovered = queue.claim(binding, 2100)!!
            assertNotEquals(first.token, recovered.token)
            assertEquals(2, recovered.attempt)
            assertArrayEquals(first.body, recovered.body)
            assertFalse(queue.acknowledge(first, validAck(first)))
            assertFalse(queue.retry(first, 3000))
            assertFalse(queue.hold(first, HoldReason.CONFLICT))
            assertFalse(queue.terminal(first, TerminalReason.INVALID_PAYLOAD))
            for (bad in listOf(validAck(recovered).copy(eventId = UUID.randomUUID().toString()),
                validAck(recovered).copy(schemaVersion = 2), validAck(recovered).copy(httpStatus = 204),
                validAck(recovered).copy(receiptStatus = "credited"), validAck(recovered).copy(receivedAt = -1),
                validAck(recovered).copy(processingStatus = "unknown"))) assertFalse(queue.acknowledge(recovered, bad))
            assertTrue(queue.retry(recovered, 5000))
            assertNull(queue.claim(binding, 4999))
            val retry = queue.claim(binding, 5000)!!
            assertArrayEquals(first.body, retry.body)
            assertTrue(queue.acknowledge(retry, validAck(retry).copy(httpStatus = 200, receiptStatus = "duplicate", processingStatus = "credited")))
            assertEquals("ACKED", queue.rows().single().state)
            assertEquals(1, IdentityStore(db).stats().events)
            assertEquals(1000, IdentityStore(db).events().single().observedAt)
            assertNull(queue.claim(binding, 100_000))
        } finally { executor.shutdownNow(); second.close() }
    }
    @Test fun noRetroactiveBindingAndScopeOrIdentityChangeHoldsPendingWork() = withDatabase { db, _ ->
        val store = IdentityStore(db)
        val queue = OutboxStore(db)
        val legacy = store.record(identityInput("unbound"), enabled)
        queue.activate(binding)
        store.record(identityInput("unbound"), enabled, binding)
        assertTrue(queue.rows().isEmpty())
        assertEquals("UNBOUND", store.event(legacy.eventId!!)!!.state)
        store.record(identityInput("bound"), enabled, binding)
        val old = queue.claim(binding, 1000)!!
        val changed = binding.copy(version = 2)
        queue.activate(changed)
        assertEquals("HELD", queue.rows().single().state)
        assertFalse(queue.acknowledge(old, validAck(old)))
        assertNull(queue.claim(binding, 2000))
        // A capture racing revocation cannot use its stale snapshot.
        val raced = store.record(identityInput("stale"), enabled, binding)
        assertEquals("UNBOUND", store.event(raced.eventId!!)!!.state)
        store.record(identityInput("new"), enabled, changed)
        val fresh = queue.claim(changed, 2000)!!
        store.record(identityInput("new").copy(postTime = 999), enabled, changed)
        assertFalse(queue.retry(fresh, 3000))
        assertEquals("IDENTITY_CONFLICT", queue.rows().single { it.eventId == fresh.eventId }.reason)
        store.record(identityInput("revoked"), enabled, changed)
        queue.revoke(Provider.WXPAY)
        assertNull(queue.claim(changed, 5000))
        assertTrue(queue.rows().all { it.state == "HELD" })
        queue.activate(changed)
        assertNull(queue.claim(changed, 5000))
    }
    @Test fun fixtureCannotQueueNotificationSourceOrOtherProviderBinding() = withDatabase { db, _ ->
        val queue = OutboxStore(db)
        val store = IdentityStore(db)
        val production = binding.copy(sourceClass = BindingSnapshot.SourceClass.NOTIFICATION)
        queue.activate(production)
        store.record(identityInput("wrong-source"), enabled, production)
        val otherProvider = syntheticBinding(Provider.ALIPAY)
        queue.activate(otherProvider)
        store.record(identityInput("wrong-provider"), enabled, otherProvider)
        assertTrue(queue.rows().isEmpty())
        assertTrue(store.events().all { it.state == "UNBOUND" })
    }
    @Test fun actualOutboxAbortRollsBackWholeObservationTransaction() = withDatabase { db, _ ->
        val queue = OutboxStore(db)
        queue.activate(binding)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_test_outbox BEFORE INSERT ON outbox BEGIN SELECT RAISE(ABORT, 'synthetic abort'); END")
        val store = IdentityStore(db)
        assertTrue(runCatching { store.record(identityInput(), enabled, binding) }.isFailure)
        assertEquals(0, store.stats().observations)
        assertEquals(0, store.stats().events)
        assertTrue(queue.rows().isEmpty())
        assertNull(db.observerDao().metadata("installation_epoch"))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_test_outbox")
        store.record(identityInput(), enabled, binding)
        assertEquals(1, queue.rows().size)
    }
    @Test fun capacityRetainsTombstonesAndExistingEventsStillAck() = withDatabase { db, _ ->
        val queue = OutboxStore(db)
        queue.activate(binding)
        val small = IdentityStore(db, queueCapacity = 1)
        small.record(identityInput("first"), enabled, binding)
        assertNull(small.record(identityInput("full"), enabled, binding).eventId)
        assertTrue(small.stats().capacityBlocked)
        assertTrue(small.stats().gap)
        val recoveredCapacity = IdentityStore(db)
        assertNull(recoveredCapacity.record(identityInput("full"), enabled, binding).eventId)
        val tinyPayload = IdentityStore(db, payloadBudget = 1)
        assertNull(tinyPayload.record(identityInput("bytes-full"), enabled).eventId)
        assertNull(recoveredCapacity.record(identityInput("bytes-full"), enabled, binding).eventId)
        val lease = queue.claim(binding, 2000)!!
        assertTrue(queue.acknowledge(lease, validAck(lease)))
        assertEquals(3, small.stats().observations)
        assertEquals(1, small.stats().events)
        assertEquals(1, queue.rows().size)
    }
    @Test fun heldAndTerminalAndAcknowledgedRecordsAreRetained() = withDatabase { db, _ ->
        val store = IdentityStore(db)
        val queue = OutboxStore(db)
        queue.activate(binding)
        store.record(identityInput("ack"), enabled, binding)
        val lease = queue.claim(binding, 1000)!!
        assertTrue(queue.acknowledge(lease, validAck(lease)))
        store.record(identityInput("ack").copy(postTime = 999), enabled, binding)
        assertEquals("ACKED", queue.rows().single().state)
        store.record(identityInput("terminal"), enabled, binding)
        assertTrue(queue.terminal(queue.claim(binding, 1000)!!, TerminalReason.INVALID_PAYLOAD))
        store.record(identityInput("held"), enabled, binding)
        assertTrue(queue.hold(queue.claim(binding, 1000)!!, HoldReason.AUTHENTICATION))
        queue.revoke(Provider.WXPAY)
        assertEquals(setOf("ACKED", "HELD", "TERMINAL"), queue.rows().map { it.state }.toSet())
        assertEquals(3, store.events().size)
    }
    @Test fun schemaOneMigrationPreservesLegacyBytesAndCreatesNoRetroactiveQueue() {
        val name = "test-migration-${UUID.randomUUID()}.db"
        val id = "11111111-1111-4111-8111-111111111111"
        val bytes = "synthetic-v1-payload".toByteArray()
        val epoch = "test-epoch"
        val key = IdentityEncoding.identity(epoch, 0, "dev.epay.observer.fixture", "legacy-key")
        try {
            migration.createDatabase(name, 1).use { old ->
                old.execSQL("INSERT INTO metadata VALUES ('installation_epoch', ?)", arrayOf(epoch))
                old.execSQL("INSERT INTO observation_identities VALUES (?, 'dev.epay.observer.fixture', 0, 1, 'source', 'fields', 100, 1000, 2000, 1000, 1, 1, 0, 0, 0, ?)", arrayOf(key, id))
                old.execSQL("INSERT INTO local_events VALUES (?, ?, 'wxpay', 1000, 'CNY', 'fixture-wx-v1', 1000, ?, 'UNBOUND')", arrayOf(id, key, bytes))
            }
            migration.runMigrationsAndValidate(name, 2, true, ObserverDatabase.MIGRATION_1_2).close()
            val migrated = ObserverDatabase.open(context, name)
            try {
                val store = IdentityStore(migrated)
                assertEquals(epoch, store.epoch())
                assertArrayEquals(bytes, store.event(id)!!.payload)
                assertEquals(1000, store.event(id)!!.observedAt)
                val queue = OutboxStore(migrated)
                queue.activate(binding)
                assertEquals(id, store.record(identityInput("legacy-key"), enabled, binding).eventId)
                assertArrayEquals(bytes, store.event(id)!!.payload)
                assertTrue(queue.rows().isEmpty())
            } finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
    }
}
