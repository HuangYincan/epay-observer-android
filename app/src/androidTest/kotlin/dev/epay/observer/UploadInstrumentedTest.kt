package dev.epay.observer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
class UploadInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(block: (ObserverDatabase, ActiveCredential, String) -> Unit) {
        val name = "upload-${UUID.randomUUID()}.db"; val db = ObserverDatabase.open(context, name)
        val binding = syntheticBinding().copy(deviceId = "upload-test-device")
        val keyId = "test-${UUID.randomUUID()}"
        try {
            HmacKeyVault(context).install(binding, keyId, vaultMaterial())
            PairingStore(db).activate(PairingEnrollment(UUID.randomUUID().toString(), binding, keyId, byteArrayOf(), 0, 1_000_000))
            val active = PairingStore(db).active(Provider.WXPAY)!!
            val event = IdentityStore(db).record(identityInput("upload"), Provider.entries.toSet(), credentialsAtCapture = mapOf(Provider.WXPAY to active))
            block(db, active, event.eventId!!)
        } finally { HmacKeyVault(context).remove(binding, keyId); db.close(); context.deleteDatabase(name) }
    }
    private fun ack(event: String, status: Int = 202) = HttpResult(status, JSONObject().put("schema_version", 1)
        .put("event_id", event).put("receipt_status", if (status == 202) "accepted" else "duplicate")
        .put("processing_status", "pending").put("received_at_ms", 2000).toString().toByteArray(), true)

    @Test fun lostAckAndNetworkRetryReuseBytesButRefreshEnvelope() = fixture { db, _, id ->
        val requests = mutableListOf<SignedRequest>(); var now = 2000L
        val client = EventClient { _, request ->
            requests += request
            if (requests.size == 1) throw java.io.IOException("synthetic lost ACK")
            ack(id, 200)
        }
        val delivery = OutboxDelivery(context, db, client, { now }, RetryPolicy { 1 })
        assertEquals("RETRY", delivery.sendNext(Provider.WXPAY)!!.state)
        assertEquals("RETRY", OutboxStore(db).rows().single().state)
        assertNull(delivery.sendNext(Provider.WXPAY))
        now++
        assertEquals("ACKED", delivery.sendNext(Provider.WXPAY)!!.state)
        val row = OutboxStore(db).rows().single()
        assertEquals("ACKED", row.state); assertEquals(2L, row.attempts)
        assertArrayEquals(requests[0].bodyBytes(), requests[1].bodyBytes())
        assertEquals(id, JSONObject(String(requests[1].bodyBytes())).getString("event_id"))
        assertNotEquals(requests[0].headers()["X-Observer-Request-Id"], requests[1].headers()["X-Observer-Request-Id"])
        assertNotEquals(requests[0].headers()["X-Observer-Sent-At"], requests[1].headers()["X-Observer-Sent-At"])
        assertNull(delivery.sendNext(Provider.WXPAY))
    }
    @Test fun errorsRetryPauseOrTerminateWithoutInventingReceipts() {
        val scenarios = listOf(
            Triple(401, "invalid_signature", "HELD"), Triple(403, "revoked", "HELD"),
            Triple(409, "event_conflict", "HELD"), Triple(429, "rate_limited", "RETRY"),
            Triple(503, "unavailable", "RETRY"), Triple(307, "", "HELD"),
            Triple(400, "unsupported_version", "TERMINAL"), Triple(400, "", "RETRY"),
            Triple(202, "", "RETRY"), Triple(413, "body_too_large", "TERMINAL"),
        )
        for ((status, code, state) in scenarios) fixture { db, credential, _ ->
            val before = OutboxStore(db).rows().single().body
            val response = HttpResult(status, if (code.isEmpty()) byteArrayOf() else JSONObject().put("schema_version", 1).put("code", code).toString().toByteArray(), code.isNotEmpty(), if (status == 429) "120" else null)
            val delivery = OutboxDelivery(context, db, EventClient { _, _ -> response }, { 2000 }, RetryPolicy { 1 })
            assertTrue(delivery.sendNext(Provider.WXPAY)!!.applied)
            val row = OutboxStore(db).rows().single()
            assertEquals("HTTP $status/$code", state, row.state)
            assertArrayEquals(before, row.body); assertNull(row.receivedAt)
            if (status == 429) assertEquals(122000L, row.nextAttemptAt)
            if (status == 400 && code == "unsupported_version") assertEquals("UNSUPPORTED_VERSION", row.reason)
            if (status in setOf(401, 403, 307)) {
                assertFalse(PairingStore(db).uploadAllowed(credential))
                assertNull(delivery.sendNext(Provider.WXPAY))
                PairingStore(db).resumeUploads(Provider.WXPAY)
                assertTrue(PairingStore(db).uploadAllowed(credential))
            }
            if (status == 409) assertTrue(PairingStore(db).uploadAllowed(credential))
        }
    }
    @Test fun delayedAckCannotOverridePauseAndMissingKeyCannotSend() = fixture { db, credential, id ->
        val delivery = OutboxDelivery(context, db, EventClient { _, _ ->
            PairingStore(db).pause(Provider.WXPAY)
            ack(id)
        }, { 2000 })
        assertFalse(delivery.sendNext(Provider.WXPAY)!!.applied)
        assertEquals("HELD", OutboxStore(db).rows().single().state)
        PairingStore(db).activate(PairingEnrollment(credential.enrollmentId, credential.binding, credential.keyId, byteArrayOf(), 0, 1_000_000))
        HmacKeyVault(context).remove(credential.binding, credential.keyId)
        val blocked = OutboxDelivery(context, db, EventClient { _, _ -> error("network must not be called") }, { 2000 })
        assertEquals("KEY_UNAVAILABLE", blocked.sendNext(Provider.WXPAY)!!.state)
        assertEquals("HELD", OutboxStore(db).rows().single().state)
    }
}
