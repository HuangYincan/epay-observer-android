package dev.epay.observer

import dev.epay.observer.protocol.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReceiptVectorsTest {
    @Test fun sharedAcknowledgementsRequireExactEventAndStrictJson() {
        val data = javaClass.classLoader!!.getResourceAsStream("receipts.json")!!.bufferedReader().use { JSONObject(it.readText()) }
        val cases = data.getJSONArray("acks")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val response = HttpResult(case.getInt("status"), case.getString("body").toByteArray(), true)
            assertEquals(case.getString("id"), case.getBoolean("valid_ack"), EventResponse.ack(response, data.getString("expected_event_id")) != null)
        }
    }
    @Test fun protocolErrorsMustMatchHttpStatus() {
        val body = "{\"schema_version\":1,\"code\":\"revoked\"}".toByteArray()
        assertEquals("revoked", EventResponse.error(HttpResult(403, body, true)))
        assertNull(EventResponse.error(HttpResult(400, body, true)))
        assertNull(EventResponse.error(HttpResult(403, body, false)))
        assertFalse(HttpResult(403, body, true).toString().contains("revoked"))
    }
}
