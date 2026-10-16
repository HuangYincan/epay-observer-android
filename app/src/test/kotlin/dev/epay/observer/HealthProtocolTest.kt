package dev.epay.observer

import dev.epay.observer.protocol.*
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HealthProtocolTest {
    @Test fun sharedVectorAndEventDomainStaySeparate() {
        val value = JSONObject(javaClass.getResourceAsStream("/health.json")!!.bufferedReader().use { it.readText() })
        val key = SecretKeySpec(value.getString("key_hex").chunked(2).map { it.toInt(16).toByte() }.toByteArray(), "HmacSHA256")
        val signer = RequestSigner({ value.getLong("sent_at_ms") }, { value.getString("request_id") })
        val request = signer.prepareHealth(value.getString("key_id"), key)
        assertEquals(value.getString("path"), request.path)
        assertArrayEquals(value.getString("body_utf8").toByteArray(), request.bodyBytes())
        assertEquals(value.getString("signature_hex"), request.headers()["X-Observer-Signature"])
        assertNotEquals(request.headers()["X-Observer-Signature"],
            signer.prepare(request.bodyBytes(), value.getString("key_id"), key).headers()["X-Observer-Signature"])
    }
    @Test fun onlyExactHealthSuccessIsAcceptedAndNeverAnEventAck() {
        val valid = "{\"schema_version\":1,\"status\":\"authorized\",\"server_time_ms\":2000}"
        assertTrue(HealthProtocol.authorized(HttpResult(200, valid.toByteArray(), true)))
        for (body in listOf(valid.replace("2000", "2e3"), valid.replace("2000", "null"),
            valid.replace("authorized", "credited"), valid.dropLast(1) + ",\"order_id\":\"fake\"}",
            valid.dropLast(1) + ",\"status\":\"authorized\"}", valid + "{}")) {
            assertFalse(body, HealthProtocol.authorized(HttpResult(200, body.toByteArray(), true)))
        }
        assertFalse(HealthProtocol.authorized(HttpResult(202, valid.toByteArray(), true)))
        assertFalse(HealthProtocol.authorized(HttpResult(200, valid.toByteArray(), false)))
        assertNull(EventResponse.ack(HttpResult(200, valid.toByteArray(), true), "11111111-1111-4111-8111-111111111111"))
    }
}
