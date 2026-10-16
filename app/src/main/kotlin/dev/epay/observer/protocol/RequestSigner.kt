package dev.epay.observer.protocol

import dev.epay.observer.storage.IdentityEncoding
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.SecretKey

/** Deliberately not a data class: diagnostic strings must not print signed bodies or headers. */
class SignedRequest internal constructor(body: ByteArray, headers: Map<String, String>,
    private val route: String = "/api/observer/v1/events") {
    private val frozenBody = body.copyOf()
    private val frozenHeaders = headers.toMap()
    val method: String get() = "POST"
    val path: String get() = route
    fun bodyBytes(): ByteArray = frozenBody.copyOf()
    fun headers(): Map<String, String> = frozenHeaders.toMap()
    override fun toString(): String = "SignedRequest(redacted)"
}

/** Signs exact persisted bytes. Pairing authorizes the key; this component never imports/exports it. */
class RequestSigner(
    private val clock: () -> Long = System::currentTimeMillis,
    private val newRequestId: () -> String = { UUID.randomUUID().toString() },
) {
    fun prepare(body: ByteArray, keyId: String, key: SecretKey): SignedRequest =
        sign(body, keyId, key, "/api/observer/v1/events", "epay-observer-v1")
    fun prepareHealth(keyId: String, key: SecretKey): SignedRequest =
        sign("{\"schema_version\":1}".toByteArray(Charsets.UTF_8), keyId, key, HealthProtocol.PATH, "epay-observer-health-v1")
    private fun sign(body: ByteArray, keyId: String, key: SecretKey, path: String, domain: String): SignedRequest {
        require(body.size in 1..16_384)
        require(Regex("[A-Za-z0-9_-]{1,64}").matches(keyId))
        val sentAt = clock()
        require(IdentityEncoding.validTime(sentAt))
        val requestId = newRequestId()
        require(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(requestId))
        val frozen = body.copyOf()
        val hash = hex(MessageDigest.getInstance("SHA-256").digest(frozen))
        val input = listOf(domain, "POST", path, keyId,
            sentAt.toString(), requestId, hash).joinToString("\n").toByteArray(Charsets.UTF_8)
        val signature = Mac.getInstance("HmacSHA256").run { init(key); hex(doFinal(input)) }
        return SignedRequest(frozen, linkedMapOf(
            "Content-Type" to "application/json; charset=utf-8",
            "X-Observer-Version" to "1", "X-Observer-Key-Id" to keyId,
            "X-Observer-Sent-At" to sentAt.toString(), "X-Observer-Request-Id" to requestId,
            "X-Observer-Signature" to signature,
        ), path)
    }
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
