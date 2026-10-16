package dev.epay.observer

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Contract evidence only: no production signer/uploader is introduced by #9.
class ProtocolGoldenVectorsTest {
    private val vectors = requireNotNull(javaClass.getResourceAsStream("/vectors.json")).use {
        JSONObject(it.bufferedReader(Charsets.UTF_8).readText()).getJSONArray("vectors")
    }

    private fun bytes(value: String) = Base64.getDecoder().decode(value)
    private fun hex(value: ByteArray) = value.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun digest(value: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(value))
    private fun canonical(vector: JSONObject, body: ByteArray) = listOf(
        "epay-observer-v1", vector.getString("method"), vector.getString("path"),
        vector.getString("key_id"), vector.getString("sent_at_ms"),
        vector.getString("request_id"), digest(body),
    ).joinToString("\n").toByteArray(Charsets.UTF_8)

    private fun sign(vector: JSONObject, input: ByteArray): String {
        val key = vector.getString("key_hex").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return hex(Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(input)
        })
    }

    @Test fun exactBytesAgreeWithCommittedPythonJavaGoldens() {
        assertEquals(5, vectors.length())
        for (index in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(index)
            val body = bytes(vector.getString("body_base64"))
            val input = canonical(vector, body)
            assertEquals(vector.getString("body_sha256"), digest(body))
            assertEquals(vector.getString("signing_input_base64"), Base64.getEncoder().encodeToString(input))
            assertEquals(vector.getString("signature_hex"), sign(vector, input))
        }
        assertTrue(bytes(vectors.getJSONObject(1).getString("body_base64")).toString(Charsets.UTF_8).contains("合成流水"))
    }

    @Test fun bodyPathAndNewlineChangesInvalidateAllGoldenSignatures() {
        for (index in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(index)
            val body = bytes(vector.getString("body_base64"))
            val input = canonical(vector, body).toString(Charsets.UTF_8)
            val changes = listOf(
                (input + "\n").toByteArray(),
                input.replace("\n", "\r\n").toByteArray(),
                input.replace("/events\n", "/events/\n").toByteArray(),
                canonical(vector, body + " ".toByteArray()),
            )
            for (changed in changes) {
                assertFalse(vector.getString("signature_hex") == sign(vector, changed))
            }
        }
    }

    @Test fun retryKeepsEventBytesButChangesRequestAndSignature() {
        val first = vectors.getJSONObject(0)
        val retry = vectors.getJSONObject(2)
        assertEquals(first.getString("body_base64"), retry.getString("body_base64"))
        for (field in listOf("request_id", "sent_at_ms", "signature_hex")) {
            assertFalse(first.getString(field) == retry.getString(field))
        }
    }
}
