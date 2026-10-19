package dev.epay.observer.storage

import dev.epay.observer.parsing.ParseResult
import dev.epay.observer.capture.ObservationInput

/** Only immutable, locally reviewed capture capabilities may use a notification binding. */
object OutboundPolicy {
    fun permits(binding: BindingSnapshot, candidate: ParseResult.Candidate): Boolean {
        val permit = candidate.pilotPermit ?: return false
        return dev.epay.observer.pilot.PilotBuild.allowed && permit.configuration.review.complete() &&
            binding == permit.configuration.binding && binding.sourceClass == BindingSnapshot.SourceClass.NOTIFICATION &&
            binding.provider == dev.epay.observer.parsing.Provider.ALIPAY && candidate.provider == binding.provider &&
            candidate.ruleVersion == dev.epay.observer.pilot.AlipayReceiptSyntax.VERSION && candidate.currency == "CNY" &&
            candidate.amountMinor == permit.configuration.amountMinor
    }

    fun identityForNewObservation(input: ObservationInput, candidate: ParseResult.Candidate,
        binding: BindingSnapshot, ingressGap: Boolean): EvidenceIdentity =
        if (permits(binding, candidate) && !ingressGap && candidate.pilotPermit?.matches(input) == true)
            EvidenceIdentity.DISTINCT else EvidenceIdentity.UNCERTAIN
}
