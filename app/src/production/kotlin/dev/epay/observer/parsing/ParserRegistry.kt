package dev.epay.observer.parsing

import dev.epay.observer.capture.NotificationObservation

object ParserRegistry {
    const val available = false
    @Suppress("UNUSED_PARAMETER")
    fun analyze(observation: NotificationObservation, enabled: Set<Provider>): ParseResult =
        ParseResult.Unsupported(ParseReason.NO_VERIFIED_RULE)
}
