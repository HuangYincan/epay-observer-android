package dev.epay.observer.pilot

import dev.epay.observer.protocol.StrictObject
import dev.epay.observer.parsing.Provider
import dev.epay.observer.storage.BindingSnapshot

/** Strict, bounded, private operator handoff. It contains no merchant/device secrets or order hints. */
object PilotConfigurationCodec {
    fun decode(bytes: ByteArray): PilotConfiguration {
        val o = StrictObject.parse(bytes)
        o.requireFields("schema_version", "origin", "environment", "device_id", "receiver_id", "binding_version",
            "provider", "source_class", "parser_version", "amount_minor", "server_armed_at_ms", "server_expires_at_ms",
            "evidence_ref", "template_verified", "chat_boundary_verified", "identity_policy_approved",
            "amount_policy_approved", "newapi_contract_verified")
        require(o.number("schema_version") == 1L && o.text("provider") == "alipay" &&
            o.text("source_class") == "notification" && o.text("parser_version") == AlipayReceiptSyntax.VERSION)
        fun verified(name: String): Boolean = o.number(name, 0, 1) == 1L
        val review = PilotReview(o.text("evidence_ref"), verified("template_verified"), verified("chat_boundary_verified"),
            verified("identity_policy_approved"), verified("amount_policy_approved"), verified("newapi_contract_verified"))
        require(review.complete()) { "review_incomplete" }
        val binding = BindingSnapshot(o.text("origin"), o.text("environment"), o.text("device_id"), o.text("receiver_id"),
            o.number("binding_version", 1, Int.MAX_VALUE.toLong()).toInt(), Provider.ALIPAY, BindingSnapshot.SourceClass.NOTIFICATION)
        return PilotConfiguration(binding, o.number("amount_minor", 1, dev.epay.observer.parsing.MinorAmount.MAX),
            o.number("server_armed_at_ms"), o.number("server_expires_at_ms"), review)
    }
}
