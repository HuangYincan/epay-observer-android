package dev.epay.observer.storage

import dev.epay.observer.parsing.ParseResult
import dev.epay.observer.capture.ObservationInput

/** No verified production source/parser/binding combination exists yet. */
object OutboundPolicy {
    @Suppress("UNUSED_PARAMETER")
    fun permits(binding: BindingSnapshot, candidate: ParseResult.Candidate): Boolean = false

    @Suppress("UNUSED_PARAMETER")
    fun identityForNewObservation(input: ObservationInput, candidate: ParseResult.Candidate,
        binding: BindingSnapshot, ingressGap: Boolean): EvidenceIdentity = EvidenceIdentity.UNCERTAIN
}
