package dev.epay.observer.storage

import dev.epay.observer.capture.*
import dev.epay.observer.parsing.*
import java.nio.ByteBuffer
import java.security.MessageDigest

object IdentityEncoding {
    /** Length prefixes distinguish null, empty, delimiters and field boundaries. */
    fun digest(vararg fields: String?): String {
        val hash = MessageDigest.getInstance("SHA-256")
        fields.forEach { value ->
            val bytes = value?.toByteArray(Charsets.UTF_8)
            hash.update(ByteBuffer.allocate(4).putInt(bytes?.size ?: -1).array())
            if (bytes != null) hash.update(bytes)
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    fun identity(epoch: String, user: Long, packageName: String, key: String): String =
        digest("observation-identity-v1", epoch, user.toString(), packageName, key)
    fun source(source: SourceIdentity): String = digest("source-v1", source.packageName, source.opPackage,
        source.user.toString(), source.version.toString(), *source.signers.sorted().toTypedArray())
    fun fields(f: NotificationFields): String = digest("fields-v1", f.title, f.text, f.bigText,
        f.channel, f.category, f.flags.toString(), f.group, f.unsupported.toString(), f.template, f.hasAdditionalText.toString())

    fun localPayload(eventId: String, candidate: ParseResult.Candidate, observedAt: Long): ByteArray {
        require(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(eventId))
        require(candidate.amountMinor in 1..MinorAmount.MAX && candidate.currency == "CNY")
        require(Regex("[A-Za-z0-9._-]{1,64}").matches(candidate.ruleVersion))
        require(validTime(observedAt))
        val provider = candidate.provider.name.lowercase(java.util.Locale.ROOT)
        return ("{\"kind\":\"local-candidate-v1\",\"event_id\":\"$eventId\"," +
            "\"provider\":\"$provider\",\"amount_minor\":${candidate.amountMinor},\"currency\":\"CNY\"," +
            "\"observed_at_ms\":$observedAt,\"parser_version\":\"${candidate.ruleVersion}\"," +
            "\"identity_confidence\":\"uncertain\"}").toByteArray(Charsets.UTF_8)
    }
    fun validTime(value: Long) = value in 0..9_007_199_254_740_991L
}
