package dev.epay.observer.protocol

import android.content.Context
import dev.epay.observer.parsing.Provider
import dev.epay.observer.security.HmacKeyVault
import dev.epay.observer.security.VaultFailure
import dev.epay.observer.storage.*
import java.io.IOException

data class DeliveryResult(val state: String, val applied: Boolean)

/** One durable lease per call. Background scheduling and UI both use this same
 * delivery entry point; retries never rebuild a body or invent an event ID. */
class OutboxDelivery(context: Context, database: ObserverDatabase,
    private val client: EventClient = EventTransport(), private val clock: () -> Long = System::currentTimeMillis,
    private val retry: RetryPolicy = RetryPolicy()) {
    private val vault = HmacKeyVault(context)
    private val pairing = PairingStore(database)
    private val outbox = OutboxStore(database)

    fun sendNext(provider: Provider): DeliveryResult? {
        val credential = pairing.active(provider) ?: return null
        if (!pairing.uploadAllowed(credential)) return null
        val now = clock()
        if (!IdentityEncoding.validTime(now) || now < credential.notBefore || now >= credential.expiresAt) {
            pairing.pauseUploads(credential, HoldReason.CLOCK_RANGE)
            return DeliveryResult("CLOCK_RANGE", true)
        }
        val key = try { vault.load(credential.binding, credential.keyId) } catch (_: VaultFailure) { null }
        if (key == null) {
            pairing.pauseUploads(credential, HoldReason.AUTHENTICATION)
            return DeliveryResult("KEY_UNAVAILABLE", true)
        }
        val lease = outbox.claim(credential.binding, now) ?: return null
        if (!pairing.uploadAllowed(credential)) return DeliveryResult("STALE_AUTHORITY", outbox.hold(lease, HoldReason.SCOPE))
        val request = try { RequestSigner(clock).prepare(lease.body, credential.keyId, key) }
        catch (_: IllegalArgumentException) {
            val applied = outbox.hold(lease, HoldReason.CLOCK_RANGE)
            if (applied) pairing.pauseUploads(credential, HoldReason.CLOCK_RANGE)
            return DeliveryResult("CLOCK_RANGE", applied)
        }
        catch (_: java.security.GeneralSecurityException) {
            val applied = outbox.hold(lease, HoldReason.AUTHENTICATION)
            if (applied) pairing.pauseUploads(credential, HoldReason.AUTHENTICATION)
            return DeliveryResult("KEY_UNAVAILABLE", applied)
        }
        val response = try { client.send(credential.binding.origin, request) } catch (_: IOException) { null }
        val ack = response?.let { EventResponse.ack(it, lease.eventId) }
        val code = response?.let(EventResponse::error)
        // Unknown 400/413/415 responses (e.g. an HTML proxy page) cannot discard an event.
        val status = response?.status?.takeUnless { it in setOf(400, 413, 415) && code == null }
        val after = clock()
        if (!IdentityEncoding.validTime(after)) {
            val applied = outbox.hold(lease, HoldReason.CLOCK_RANGE)
            if (applied) pairing.pauseUploads(credential, HoldReason.CLOCK_RANGE)
            return DeliveryResult("CLOCK_RANGE", applied)
        }
        return when (val decision = retry.decide(status, ack != null, lease.attempt, after, response?.retryAfter)) {
            DeliveryDecision.Received -> DeliveryResult("ACKED", outbox.acknowledge(lease, ack!!))
            is DeliveryDecision.Retry -> DeliveryResult("RETRY", outbox.retry(lease, decision.at))
            is DeliveryDecision.Pause -> {
                val reason = HoldReason.valueOf(decision.reason.name)
                val applied = outbox.hold(lease, reason)
                if (applied && reason != HoldReason.CONFLICT) pairing.pauseUploads(credential, reason)
                DeliveryResult(reason.name, applied)
            }
            is DeliveryDecision.Terminal -> {
                val reason = if (code == "unsupported_version") TerminalReason.UNSUPPORTED_VERSION
                    else TerminalReason.valueOf(decision.reason.name)
                DeliveryResult("TERMINAL", outbox.terminal(lease, reason))
            }
        }
    }
}
