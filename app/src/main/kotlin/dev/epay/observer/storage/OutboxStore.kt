package dev.epay.observer.storage

import dev.epay.observer.parsing.Provider
import java.util.UUID
import java.util.concurrent.Callable

/** A lease is a delivery attempt, never a payment identity or an HTTP request ID. */
data class DeliveryLease(val eventId: String, val token: String, val until: Long, val attempt: Long, val body: ByteArray)
data class ReceiptAck(val schemaVersion: Int, val eventId: String, val httpStatus: Int,
    val receiptStatus: String, val processingStatus: String, val receivedAt: Long)
enum class HoldReason { AUTHENTICATION, SCOPE, CONFLICT }
enum class TerminalReason { INVALID_PAYLOAD, UNSUPPORTED_VERSION, BODY_TOO_LARGE, UNSUPPORTED_MEDIA_TYPE }

class OutboxStore(private val database: ObserverDatabase) {
    private val dao = database.outboxDao()
    private val identities = database.observerDao()

    /** Must later be called only after authenticated pairing. No UI/intent invokes this in this batch. */
    fun activate(binding: BindingSnapshot) = database.runInTransaction {
        identities.putMetadata(MetadataRow(binding.metadataKey, binding.scope))
        dao.holdOtherScopes(binding.providerName, binding.scope)
    }
    fun revoke(provider: Provider) = database.runInTransaction {
        identities.putMetadata(MetadataRow("binding:${provider.name.lowercase(java.util.Locale.ROOT)}", "revoked"))
        dao.holdProvider(provider.name.lowercase(java.util.Locale.ROOT))
    }
    fun claim(binding: BindingSnapshot, now: Long, durationMs: Long = 60_000): DeliveryLease? =
        database.runInTransaction(Callable {
            require(IdentityEncoding.validTime(now) && durationMs in 1..300_000 && IdentityEncoding.validTime(now + durationMs))
            if (identities.metadata(binding.metadataKey) != binding.scope) return@Callable null
            val row = dao.eligible(binding.scope, now) ?: return@Callable null
            if (row.attempts == Long.MAX_VALUE) {
                dao.holdEvent(row.eventId, "ATTEMPT_LIMIT")
                return@Callable null
            }
            val token = UUID.randomUUID().toString()
            dao.lease(row.eventId, token, now + durationMs)
            DeliveryLease(row.eventId, token, now + durationMs, row.attempts + 1, row.body.copyOf())
        })

    fun acknowledge(lease: DeliveryLease, ack: ReceiptAck): Boolean {
        if (ack.schemaVersion != 1 || ack.eventId != lease.eventId || !IdentityEncoding.validTime(ack.receivedAt) ||
            ack.processingStatus !in setOf("pending", "needs_review", "credited") ||
            !(ack.httpStatus == 202 && ack.receiptStatus == "accepted" || ack.httpStatus == 200 && ack.receiptStatus == "duplicate")) return false
        return database.runInTransaction(Callable {
            dao.ack(lease.eventId, lease.token, ack.receivedAt) == 1
        })
    }
    fun retry(lease: DeliveryLease, nextAttemptAt: Long): Boolean {
        require(IdentityEncoding.validTime(nextAttemptAt))
        return dao.retry(lease.eventId, lease.token, nextAttemptAt) == 1
    }
    fun hold(lease: DeliveryLease, reason: HoldReason): Boolean =
        dao.finish(lease.eventId, lease.token, "HELD", reason.name) == 1
    fun terminal(lease: DeliveryLease, reason: TerminalReason): Boolean =
        dao.finish(lease.eventId, lease.token, "TERMINAL", reason.name) == 1
    fun rows(): List<OutboxRow> = dao.rows()
}
