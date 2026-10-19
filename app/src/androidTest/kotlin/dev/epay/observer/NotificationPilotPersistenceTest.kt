package dev.epay.observer

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.epay.observer.capture.*
import dev.epay.observer.parsing.Provider
import dev.epay.observer.pilot.*
import dev.epay.observer.storage.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Synthetic input in an independent in-memory DB; never arms a listener or uses the paired DB/network. */
class NotificationPilotPersistenceTest {
    private fun withDatabase(block: (ObserverDatabase) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context,ObserverDatabase::class.java).build()
        try { block(db) } finally { db.close() }
    }
    @Test fun isolatedNotificationCandidateUsesDurableOutboxWithoutHistoricalUpgrade() {
        assumeTrue(PilotBuild.allowed)
        val binding = BindingSnapshot("https://observer.example","synthetic-test","device","receiver",1,
            Provider.ALIPAY,BindingSnapshot.SourceClass.NOTIFICATION)
        val configuration = PilotConfiguration(binding,1234,100_000,400_000,
            PilotReview("synthetic-test-only",true,true,true,true))
        val session = NotificationPilot(configuration,100_000,10_000)
        val source = SourceIdentity(PilotAlipaySource.PACKAGE,0,PilotAlipaySource.VERSION,setOf(PilotAlipaySource.CERTIFICATE))
        val permit = session.admit(source,101_000,101_000,11_000,false)!!
        val fields = NotificationFields("你已成功收款12.34元（合成）","合成甲转入余额宝 合成乙1合成丙2合成丁",null,
            "synthetic-channel",null,0x10,"synthetic-group")
        assertNotNull(permit.readFields(101_000,11_000) { fields })
        val input = ObservationInput("synthetic-key",source,101_000,101_000,11_000,false,fields,permit)
        withDatabase { db ->
            val store = IdentityStore(db)
            val queue = OutboxStore(db)
            queue.activate(binding)
            // Previously seen/restored observations cannot be upgraded by opening a new window.
            assertNull(store.record(input.copy(restored=true,pilotPermit=null),setOf(Provider.ALIPAY),binding).eventId)
            assertNull(store.record(input,setOf(Provider.ALIPAY),binding).eventId)
            assertEquals(0,db.outboxDao().count())
        }
        withDatabase { db ->
            val store = IdentityStore(db)
            OutboxStore(db).activate(binding)
            val row = store.record(input,setOf(Provider.ALIPAY),binding)
            assertNotNull(row.eventId)
            assertEquals(row.eventId,store.record(input,setOf(Provider.ALIPAY),binding).eventId)
            assertEquals(1,db.observerDao().eventCount())
            assertEquals(1,db.outboxDao().count())
            val wire = db.outboxDao().rows().single().body.toString(Charsets.UTF_8)
            assertTrue(wire.contains("\"source_class\":\"notification\""))
            assertTrue(wire.contains("\"identity_confidence\":\"distinct\""))
            assertFalse(wire.contains("合成"));assertFalse(wire.contains("order"))
            store.record(input.copy(fields=fields.copy(title="你已成功收款99元（合成）")),setOf(Provider.ALIPAY),binding)
            assertEquals(1,db.observerDao().eventCount())
            assertEquals("HELD",db.outboxDao().rows().single().state)
            assertEquals("IDENTITY_CONFLICT",store.event(row.eventId!!)!!.state)
        }
        assertFalse(PilotRuntime.hasSession)
    }
}
