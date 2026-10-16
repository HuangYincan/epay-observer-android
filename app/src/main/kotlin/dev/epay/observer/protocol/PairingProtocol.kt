package dev.epay.observer.protocol

import dev.epay.observer.parsing.Provider
import dev.epay.observer.storage.BindingSnapshot
import java.util.Base64
import java.util.UUID

data class PairingScope(
    val grantId: String, val origin: String, val environment: String, val receiverId: String,
    val provider: Provider, val sourceClass: BindingSnapshot.SourceClass, val mode: String,
    val expectedVersion: Int, val deviceId: String, val expiresAt: Long,
) {
    init {
        require(PairingProtocol.id.matches(grantId) && mode in setOf("replace", "rotate"))
        require(expectedVersion >= 0 && expiresAt in 0..9_007_199_254_740_991)
        require(if (mode == "replace") deviceId.isEmpty() && expectedVersion < Int.MAX_VALUE
            else expectedVersion > 0 && PairingProtocol.id.matches(deviceId))
        BindingSnapshot(origin, environment, if (deviceId.isEmpty()) "pending" else deviceId,
            receiverId, maxOf(1, expectedVersion), provider, sourceClass)
    }
}

class EnrollmentRequest(val enrollmentId: String, val resumeSecret: String) {
    init { require(PairingProtocol.uuid.matches(enrollmentId) && PairingProtocol.validSecret(resumeSecret)) }
    override fun toString() = "EnrollmentRequest(redacted)"
    fun bytes(): ByteArray = ("{\"schema_version\":1,\"enrollment_id\":\"$enrollmentId\"," +
        "\"resume_secret\":\"$resumeSecret\"}").toByteArray(Charsets.UTF_8)
}

class PairingEnrollment(val enrollmentId: String, val binding: BindingSnapshot, val keyId: String,
    val material: ByteArray, val notBefore: Long, val expiresAt: Long) {
    override fun toString() = "PairingEnrollment(redacted)"
}

object PairingProtocol {
    internal val id = Regex("[A-Za-z0-9_-]{1,64}")
    internal val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    fun validSecret(value: String): Boolean = try {
        value.length == 43 && Regex("[A-Za-z0-9_-]{43}").matches(value) &&
            Base64.getUrlDecoder().decode(value).let { it.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(it) == value }
    } catch (_: IllegalArgumentException) { false }
    fun newRequest(): EnrollmentRequest {
        val material = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        return try { EnrollmentRequest(UUID.randomUUID().toString(), Base64.getUrlEncoder().withoutPadding().encodeToString(material)) }
        finally { material.fill(0) }
    }
    fun inspectBody() = "{\"schema_version\":1}".toByteArray(Charsets.UTF_8)
    fun validateRequest(bytes: ByteArray, operation: String) {
        val o = StrictObject.parse(bytes)
        if (operation == "inspect") o.requireFields("schema_version") else {
            require(operation == "redeem" || operation == "activate")
            o.requireFields("schema_version", "enrollment_id", "resume_secret")
            EnrollmentRequest(o.text("enrollment_id"), o.text("resume_secret"))
        }
        o.number("schema_version", 1, 1)
    }
    fun scope(bytes: ByteArray, origin: String): PairingScope {
        val o = StrictObject.parse(bytes)
        o.requireFields("schema_version", "grant_id", "origin", "environment", "receiver_id", "provider", "source_class", "mode", "expected_binding_version", "device_id", "expires_at_ms")
        o.number("schema_version", 1, 1); require(o.text("origin") == origin)
        return PairingScope(o.text("grant_id"), origin, o.text("environment"), o.text("receiver_id"),
            provider(o.text("provider")), source(o.text("source_class")), o.text("mode"),
            o.number("expected_binding_version", 0, Int.MAX_VALUE.toLong()).toInt(), o.text("device_id"), o.number("expires_at_ms"))
    }
    fun enrollment(bytes: ByteArray, confirmed: PairingScope, request: EnrollmentRequest): PairingEnrollment {
        val o = StrictObject.parse(bytes)
        o.requireFields("schema_version", "enrollment_id", "origin", "environment", "receiver_id", "provider", "source_class", "device_id", "binding_version", "key_id", "key_base64url", "not_before_ms", "expires_at_ms")
        o.number("schema_version", 1, 1); require(o.text("enrollment_id") == request.enrollmentId)
        val binding = BindingSnapshot(o.text("origin"), o.text("environment"), o.text("device_id"), o.text("receiver_id"),
            o.number("binding_version", 1, Int.MAX_VALUE.toLong()).toInt(), provider(o.text("provider")), source(o.text("source_class")))
        require(binding.origin == confirmed.origin && binding.environment == confirmed.environment &&
            binding.receiverId == confirmed.receiverId && binding.provider == confirmed.provider && binding.sourceClass == confirmed.sourceClass)
        require(if (confirmed.mode == "replace") binding.version == confirmed.expectedVersion + 1
            else binding.version == confirmed.expectedVersion && binding.deviceId == confirmed.deviceId)
        val key = o.text("key_base64url"); val keyId = o.text("key_id")
        require(id.matches(keyId) && validSecret(key))
        val notBefore = o.number("not_before_ms"); val expires = o.number("expires_at_ms")
        require(expires > notBefore && expires - notBefore <= 30L * 24 * 60 * 60 * 1000)
        return PairingEnrollment(request.enrollmentId, binding, keyId, Base64.getUrlDecoder().decode(key), notBefore, expires)
    }
    fun activation(bytes: ByteArray, enrollment: PairingEnrollment) {
        val o = StrictObject.parse(bytes)
        o.requireFields("schema_version", "enrollment_id", "key_id", "state")
        o.number("schema_version", 1, 1)
        require(o.text("enrollment_id") == enrollment.enrollmentId && o.text("key_id") == enrollment.keyId && o.text("state") == "active")
    }
    fun error(bytes: ByteArray): String {
        val o = StrictObject.parse(bytes); o.requireFields("schema_version", "code"); o.number("schema_version", 1, 1)
        return o.text("code").also { require(it in setOf("invalid_payload", "invalid_route", "invalid_grant", "scope_denied", "enrollment_conflict", "binding_changed", "body_too_large", "unsupported_media_type", "rate_limited", "unavailable")) }
    }
    private fun provider(value: String) = when (value) { "wxpay" -> Provider.WXPAY; "alipay" -> Provider.ALIPAY; else -> error("invalid_payload") }
    private fun source(value: String) = BindingSnapshot.SourceClass.entries.single { it.wire == value }
}
