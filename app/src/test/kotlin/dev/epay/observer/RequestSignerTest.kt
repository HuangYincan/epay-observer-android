package dev.epay.observer

import dev.epay.observer.protocol.RequestSigner
import dev.epay.observer.protocol.RetryPolicy
import java.util.Base64
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RequestSignerTest {
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "HmacSHA256")
    @Test fun actualSignerMatchesEveryCommittedGoldenIncludingUnicodeAndRetry() {
        val vectors = requireNotNull(javaClass.getResourceAsStream("/vectors.json")).use {
            JSONObject(it.bufferedReader().readText()).getJSONArray("vectors")
        }
        assertEquals(5, vectors.length())
        repeat(vectors.length()) { i ->
            val v = vectors.getJSONObject(i)
            val body = Base64.getDecoder().decode(v.getString("body_base64"))
            val fixtureKey = SecretKeySpec(v.getString("key_hex").chunked(2).map { it.toInt(16).toByte() }.toByteArray(), "HmacSHA256")
            val signed = RequestSigner({ v.getLong("sent_at_ms") }, { v.getString("request_id") }).prepare(body, v.getString("key_id"), fixtureKey)
            assertEquals(v.getString("signature_hex"), signed.headers()["X-Observer-Signature"])
            assertEquals(v.getString("method"), signed.method)
            assertEquals(v.getString("path"), signed.path)
            assertArrayEquals(body, signed.bodyBytes())
        }
    }
    @Test fun eachAttemptHasFreshEnvelopeAndFrozenBytesAreDefensivelyCopied() {
        var now = 1000L
        val body = "{\"event_id\":\"unchanged-synthetic-body\"}".toByteArray()
        val signer = RequestSigner({ now })
        val first = signer.prepare(body, "test-key", key)
        now += 100
        val retry = signer.prepare(first.bodyBytes(), "test-key", key)
        body[0] = 0
        first.bodyBytes()[0] = 0
        val mutableHeaders = first.headers().toMutableMap()
        mutableHeaders["X-Observer-Signature"] = "changed"
        assertEquals('{'.code.toByte(), first.bodyBytes()[0])
        assertArrayEquals(first.bodyBytes(), retry.bodyBytes())
        for (name in listOf("X-Observer-Request-Id", "X-Observer-Sent-At", "X-Observer-Signature")) assertNotEquals(first.headers()[name], retry.headers()[name])
        assertEquals(setOf("Content-Type", "X-Observer-Version", "X-Observer-Key-Id", "X-Observer-Sent-At", "X-Observer-Request-Id", "X-Observer-Signature"), first.headers().keys)
        assertEquals("SignedRequest(redacted)", first.toString())
    }
    @Test fun invalidEnvelopeAndBodyBoundsFailBeforeSending() {
        val signer = RequestSigner({ 0 }, { "33333333-3333-4333-8333-333333333333" })
        for (keyId in listOf("", "x\ny", "x y", "密钥", "x".repeat(65))) assertTrue(runCatching { signer.prepare(byteArrayOf(1), keyId, key) }.isFailure)
        for (size in listOf(0, 16385)) assertTrue(runCatching { signer.prepare(ByteArray(size), "key", key) }.isFailure)
        assertEquals(16384, signer.prepare(ByteArray(16384), "key", key).bodyBytes().size)
        for (time in listOf(-1L, RetryPolicy.MAX_TIME + 1)) assertTrue(runCatching { RequestSigner({ time }).prepare(byteArrayOf(1), "key", key) }.isFailure)
        assertTrue(runCatching { RequestSigner({ 1 }, { "not-a-uuid" }).prepare(byteArrayOf(1), "key", key) }.isFailure)
    }
}
