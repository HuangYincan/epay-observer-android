package dev.epay.observer.parsing

import dev.epay.observer.capture.NotificationObservation

object ParserRegistry {
    val available: Boolean get() = dev.epay.observer.pilot.PilotRuntime.hasSession && dev.epay.observer.pilot.PilotRuntime.running(
        System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime())
    fun analyze(observation: NotificationObservation, enabled: Set<Provider>): ParseResult =
        if (dev.epay.observer.pilot.PilotBuild.allowed) dev.epay.observer.pilot.AlipayReceiptSyntax.analyze(observation, enabled)
        else ParseResult.Unsupported(ParseReason.NO_VERIFIED_RULE)
}
