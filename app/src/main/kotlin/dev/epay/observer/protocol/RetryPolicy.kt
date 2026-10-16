package dev.epay.observer.protocol

import dev.epay.observer.storage.IdentityEncoding
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Locale
import kotlin.random.Random

sealed interface DeliveryDecision {
    data object Received : DeliveryDecision
    data class Retry(val at: Long) : DeliveryDecision
    data class Pause(val reason: PauseReason) : DeliveryDecision
    data class Terminal(val reason: RejectionReason) : DeliveryDecision
}
enum class PauseReason { AUTHENTICATION, SCOPE, CONFLICT, REDIRECT, UNEXPECTED_RESPONSE, CLOCK_RANGE }
enum class RejectionReason { INVALID_PAYLOAD, BODY_TOO_LARGE, UNSUPPORTED_MEDIA_TYPE }

/** Pure policy. verifiedAck must come from a separate strict decoder and event association check. */
class RetryPolicy(private val jitter: (Long) -> Long = { inclusiveMax -> Random.nextLong(inclusiveMax + 1) }) {
    fun decide(status: Int?, verifiedAck: Boolean, attempt: Long, now: Long, retryAfter: String? = null): DeliveryDecision {
        require(IdentityEncoding.validTime(now) && attempt > 0)
        if ((status == 200 || status == 202) && verifiedAck) return DeliveryDecision.Received
        return when {
            status == 401 -> DeliveryDecision.Pause(PauseReason.AUTHENTICATION)
            status == 403 -> DeliveryDecision.Pause(PauseReason.SCOPE)
            status == 409 -> DeliveryDecision.Pause(PauseReason.CONFLICT)
            status == 400 -> DeliveryDecision.Terminal(RejectionReason.INVALID_PAYLOAD)
            status == 413 -> DeliveryDecision.Terminal(RejectionReason.BODY_TOO_LARGE)
            status == 415 -> DeliveryDecision.Terminal(RejectionReason.UNSUPPORTED_MEDIA_TYPE)
            status != null && status in 300..399 -> DeliveryDecision.Pause(PauseReason.REDIRECT)
            status == null || status == 429 || status in 500..599 || status in 200..299 -> {
                val ceiling = minOf(3_600_000L, 10_000L shl minOf(attempt - 1, 9).toInt())
                val delay = jitter(ceiling).also { require(it in 0..ceiling) }
                if (delay > MAX_TIME - now) DeliveryDecision.Pause(PauseReason.CLOCK_RANGE)
                else DeliveryDecision.Retry(maxOf(now + delay,
                    if (status == 429 || status == 503) retryAfterAt(retryAfter, now) ?: now else now))
            }
            else -> DeliveryDecision.Pause(PauseReason.UNEXPECTED_RESPONSE)
        }
    }
    companion object {
        const val MAX_TIME = 9_007_199_254_740_991L
        private val httpDate = DateTimeFormatter.ofPattern("EEE, dd MMM uuuu HH:mm:ss 'GMT'", Locale.US)
            .withResolverStyle(ResolverStyle.STRICT)
        fun retryAfterAt(header: String?, now: Long): Long? {
            require(IdentityEncoding.validTime(now))
            val value = header?.trim(' ', '\t') ?: return null
            if (value.isEmpty() || value.length > 64) return null
            if (value.all { it in '0'..'9' }) {
                val seconds = value.toLongOrNull() ?: return MAX_TIME
                if (seconds > (MAX_TIME - now) / 1000) return MAX_TIME
                return now + seconds * 1000
            }
            return try {
                val at = LocalDateTime.parse(value, httpDate).toInstant(ZoneOffset.UTC).toEpochMilli()
                if (at > MAX_TIME) MAX_TIME else maxOf(now, at)
            } catch (_: DateTimeParseException) { null }
        }
    }
}
