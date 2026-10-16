package dev.epay.observer.storage

import dev.epay.observer.parsing.ParseResult

/** No verified production source/parser/binding combination exists yet. */
object OutboundPolicy {
    @Suppress("UNUSED_PARAMETER")
    fun permits(binding: BindingSnapshot, candidate: ParseResult.Candidate): Boolean = false
}
