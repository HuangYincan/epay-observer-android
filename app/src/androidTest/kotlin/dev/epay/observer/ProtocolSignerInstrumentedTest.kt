package dev.epay.observer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.epay.observer.protocol.RequestSigner
import dev.epay.observer.protocol.RetryPolicy
import dev.epay.observer.protocol.DeliveryDecision
import java.util.Base64
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProtocolSignerInstrumentedTest {
    @Test fun androidJcaSignsCommittedVectorsAndRuntimeParsesRetryDate() {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val vectors = testContext.assets.open("vectors.json").use { JSONObject(it.bufferedReader().readText()).getJSONArray("vectors") }
        assertEquals(3, vectors.length())
        repeat(vectors.length()) { i ->
            val v = vectors.getJSONObject(i)
            val body = Base64.getDecoder().decode(v.getString("body_base64"))
            val key = SecretKeySpec(v.getString("key_hex").chunked(2).map { it.toInt(16).toByte() }.toByteArray(), "HmacSHA256")
            val signed = RequestSigner({ v.getLong("sent_at_ms") }, { v.getString("request_id") }).prepare(body, v.getString("key_id"), key)
            assertEquals(v.getString("signature_hex"), signed.headers()["X-Observer-Signature"])
            assertArrayEquals(body, signed.bodyBytes())
            assertEquals("SignedRequest(redacted)", signed.toString())
        }
        assertEquals(DeliveryDecision.Retry(1792029600000), RetryPolicy { it }.decide(429, false, 1, 1792022400000, "Thu, 15 Oct 2026 02:00:00 GMT"))
    }
}
