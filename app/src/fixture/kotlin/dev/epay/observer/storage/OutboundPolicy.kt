package dev.epay.observer.storage

import dev.epay.observer.parsing.ParseResult
import dev.epay.observer.parsing.Provider
import dev.epay.observer.capture.ObservationInput

object OutboundPolicy {
    fun permits(binding: BindingSnapshot, candidate: ParseResult.Candidate): Boolean =
        binding.sourceClass == BindingSnapshot.SourceClass.SYNTHETIC && candidate.provider == binding.provider &&
            candidate.ruleVersion == if (candidate.provider == Provider.WXPAY) "fixture-wx-v1" else "fixture-ali-v1"

    /** Called only after parsing, for a first identity inside its Room transaction. */
    fun identityForNewObservation(input: ObservationInput, candidate: ParseResult.Candidate,
        binding: BindingSnapshot, ingressGap: Boolean): EvidenceIdentity =
        if (permits(binding, candidate) && !input.restored && !ingressGap &&
            IdentityEncoding.validTime(input.postTime) && IdentityEncoding.validTime(input.wallTime) &&
            input.postTime <= input.wallTime && input.wallTime - input.postTime <= 120_000)
            EvidenceIdentity.DISTINCT else EvidenceIdentity.UNCERTAIN
}
