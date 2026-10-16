package dev.epay.observer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.epay.observer.capture.*
import dev.epay.observer.parsing.*
import dev.epay.observer.storage.*
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

internal fun identityInput(key: String = "identity-a", restored: Boolean = false) = ObservationInput(
    key, SourceIdentity("dev.epay.observer.fixture", 0, 1, setOf("test-certificate-metadata")),
    100, 1000, 2000, restored,
    NotificationFields("微信形态 · 合成收款", "收款 10.00 元", "收款 10.00 元", "synthetic-v1", "status", 0, null,
        template = "android.app.Notification\$BigTextStyle"),
)

@RunWith(AndroidJUnit4::class)
class IdentityStoreInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val enabled = Provider.entries.toSet()
    private fun withDatabase(block: (ObserverDatabase, String) -> Unit) {
        val name = "test-identity-${UUID.randomUUID()}.db"
        val db = ObserverDatabase.open(context, name)
        try { block(db, name) } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun concurrentConnectionsProduceOneStableEventAndEpoch() = withDatabase { db, name ->
        val second = ObserverDatabase.open(context, name)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val stores = listOf(IdentityStore(db), IdentityStore(second))
            val results = (0 until 20).map { i -> executor.submit<String?> { stores[i % 2].record(identityInput(), enabled).eventId } }
            val ids = results.map { it.get(20, TimeUnit.SECONDS) }.toSet()
            assertEquals(1, ids.size); assertNotNull(ids.single())
            assertEquals(1, stores.first().stats().events)
            assertEquals(stores.first().epoch(), stores.last().epoch())
            assertArrayEquals(stores.first().event(ids.single()!!)!!.payload, stores.last().event(ids.single()!!)!!.payload)
            assertEquals(20, stores.first().record(identityInput(), enabled).deliveries - 1)
        } finally { executor.shutdownNow(); second.close() }
    }
    @Test fun differentKeysStaySeparateAndChangesCannotRewriteFrozenCandidate() = withDatabase { db, _ ->
        val store = IdentityStore(db)
        val input = identityInput()
        val first = store.record(input, enabled)
        val frozen = store.event(first.eventId!!)!!
        val second = store.record(input.copy(key = "identity-b"), enabled)
        assertNotEquals(first.eventId, second.eventId)
        store.record(input.copy(fields = input.fields.copy(text = "收款 20.00 元", bigText = "收款 20.00 元"), postTime = 200, wallTime = 3000), enabled)
        store.removed(0, input.source.packageName, input.key)
        store.record(input.copy(source = input.source.copy(version = 2)), enabled)
        val retained = store.event(first.eventId)!!
        assertArrayEquals(frozen.payload, retained.payload)
        assertEquals(1000, retained.amountMinor)
        assertEquals(1000, retained.observedAt)
        assertEquals("IDENTITY_CONFLICT", retained.state)
        assertEquals(2, store.stats().events)
    }
    @Test fun restoredUnknownAndPreviouslyDisabledKeysCannotBecomeNewPayment() = withDatabase { db, _ ->
        val store = IdentityStore(db)
        val known = store.record(identityInput(), enabled)
        val payload = store.event(known.eventId!!)!!.payload
        store.record(identityInput(restored = true).copy(wallTime = 5000), enabled)
        assertArrayEquals(payload, store.event(known.eventId)!!.payload)
        val unknown = identityInput("unknown", restored = true)
        assertNull(store.record(unknown, enabled).eventId)
        assertNull(store.record(unknown.copy(restored = false), enabled).eventId)
        val disabled = identityInput("disabled")
        assertNull(store.record(disabled, emptySet()).eventId)
        assertNull(store.record(disabled, enabled).eventId)
        assertNull(store.record(identityInput("invalid-time").copy(wallTime = -1), enabled).eventId)
        assertEquals(1, store.stats().events)
    }
    @Test fun actualSqlAbortRollsBackIdentityEpochAndCandidateTogether() = withDatabase { db, _ ->
        val store = IdentityStore(db)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_test_event BEFORE INSERT ON local_events BEGIN SELECT RAISE(ABORT, 'synthetic test abort'); END")
        assertTrue(runCatching { store.record(identityInput(), enabled) }.isFailure)
        assertEquals(0, store.stats().observations)
        assertEquals(0, store.stats().events)
        assertNull(db.observerDao().metadata("installation_epoch"))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_test_event")
        assertNotNull(store.record(identityInput(), enabled).eventId)
    }
    @Test fun capacityPreservesHistoryAndMissingTableDoesNotFakeSuccess() = withDatabase { db, _ ->
        val store = IdentityStore(db, 2)
        val first = store.record(identityInput(), enabled)
        store.record(identityInput("b"), enabled)
        assertTrue(runCatching { store.record(identityInput("c"), enabled) }.exceptionOrNull() is IdentityCapacityException)
        assertEquals(first.eventId, store.record(identityInput(), enabled).eventId)
        assertEquals(2, store.stats().observations)
        store.recordGap(); assertTrue(store.stats().gap)
        db.openHelper.writableDatabase.execSQL("DROP TABLE local_events")
        assertTrue(runCatching { store.stats() }.isFailure)
    }
    @Test fun privateRawFieldsAndCertificateMetadataAreNotWrittenToDisk() = withDatabase { db, name ->
        val store = IdentityStore(db)
        val sentinel = "SYNTHETIC_PRIVATE_NEVER_PERSIST"
        store.record(identityInput(sentinel).copy(source = identityInput().source.copy(signers = setOf(sentinel))), enabled)
        store.record(identityInput("negative").copy(fields = identityInput().fields.copy(title = sentinel, text = sentinel)), enabled)
        db.close()
        val file = context.getDatabasePath(name)
        for (suffix in listOf("", "-wal", "-shm")) {
            val part = java.io.File(file.path + suffix)
            if (part.exists()) assertFalse(part.readBytes().toString(Charsets.ISO_8859_1).contains(sentinel))
        }
    }
}
