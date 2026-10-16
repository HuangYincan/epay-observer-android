package dev.epay.observer.storage

import dev.epay.observer.parsing.ParseResult
import dev.epay.observer.parsing.Provider
import java.net.URI
import java.util.Locale

/** Non-secret scope, to be supplied by authenticated pairing; never by notification text. */
data class BindingSnapshot(
    val origin: String, val environment: String, val deviceId: String, val receiverId: String,
    val version: Int, val provider: Provider, val sourceClass: SourceClass,
) {
    enum class SourceClass(val wire: String) { SYNTHETIC("synthetic"), NOTIFICATION("notification") }
    init {
        val id = Regex("[A-Za-z0-9_-]{1,64}")
        require(listOf(environment, deviceId, receiverId).all(id::matches) && version > 0)
        val uri = URI(origin)
        require(uri.scheme == "https" && uri.host != null && uri.host == uri.host.lowercase(Locale.ROOT))
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.rawPath.isEmpty())
        require(uri.port == -1 || uri.port in 1..65535)
        require(origin == "https://${uri.host}" + if (uri.port == -1) "" else ":${uri.port}")
    }
    val providerName: String get() = provider.name.lowercase(Locale.ROOT)
    val scope: String get() = IdentityEncoding.digest("binding-v1", origin, environment, deviceId, receiverId,
        version.toString(), providerName, sourceClass.wire)
    internal val metadataKey: String get() = "binding:$providerName"
}

object EventWire {
    fun encode(eventId: String, candidate: ParseResult.Candidate, observedAt: Long, binding: BindingSnapshot): ByteArray {
        // Validate the shared fields before interpolating the protocol's restricted ASCII identifiers.
        IdentityEncoding.localPayload(eventId, candidate, observedAt)
        require(candidate.provider == binding.provider)
        return ("{\"schema_version\":1,\"event_id\":\"$eventId\",\"device_id\":\"${binding.deviceId}\"," +
            "\"receiver_id\":\"${binding.receiverId}\",\"binding_version\":${binding.version}," +
            "\"provider\":\"${binding.providerName}\",\"amount_minor\":${candidate.amountMinor},\"currency\":\"CNY\"," +
            "\"observed_at_ms\":$observedAt,\"parser_version\":\"${candidate.ruleVersion}\"," +
            "\"source_class\":\"${binding.sourceClass.wire}\",\"identity_confidence\":\"uncertain\"}")
            .toByteArray(Charsets.UTF_8).also { require(it.size <= 16_384) }
    }
}
