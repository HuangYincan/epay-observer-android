package dev.epay.observer

import dev.epay.observer.protocol.*
import org.junit.Assert.*
import org.junit.Test

class RetryPolicyTest {
    private val maximum = RetryPolicy { it }
    @Test fun httpClassificationDoesNotTreatReceiptOrRedirectAsPayment() {
        assertEquals(DeliveryDecision.Received, maximum.decide(202, true, 1, 1000))
        assertEquals(DeliveryDecision.Received, maximum.decide(200, true, 1, 1000))
        for (status in listOf(null, 200, 202, 204, 429, 500, 502, 599))
            assertEquals(DeliveryDecision.Retry(11000), maximum.decide(status, false, 1, 1000))
        for ((code, reason) in mapOf(401 to PauseReason.AUTHENTICATION, 403 to PauseReason.SCOPE, 409 to PauseReason.CONFLICT,
            300 to PauseReason.REDIRECT, 301 to PauseReason.REDIRECT, 302 to PauseReason.REDIRECT, 307 to PauseReason.REDIRECT, 308 to PauseReason.REDIRECT,
            404 to PauseReason.UNEXPECTED_RESPONSE, 418 to PauseReason.UNEXPECTED_RESPONSE))
            assertEquals(DeliveryDecision.Pause(reason), maximum.decide(code, true, 1, 1000))
        for ((code, reason) in mapOf(400 to RejectionReason.INVALID_PAYLOAD, 413 to RejectionReason.BODY_TOO_LARGE, 415 to RejectionReason.UNSUPPORTED_MEDIA_TYPE))
            assertEquals(DeliveryDecision.Terminal(reason), maximum.decide(code, false, 1, 1000))
    }
    @Test fun exponentialFullJitterBoundsAndClockOverflowAreExplicit() {
        assertEquals(DeliveryDecision.Retry(1000), RetryPolicy { 0 }.decide(null, false, 1, 1000))
        assertEquals(DeliveryDecision.Retry(21000), maximum.decide(null, false, 2, 1000))
        assertEquals(DeliveryDecision.Retry(3601000), maximum.decide(null, false, Long.MAX_VALUE, 1000))
        assertEquals(DeliveryDecision.Pause(PauseReason.CLOCK_RANGE), maximum.decide(null, false, 1, RetryPolicy.MAX_TIME))
        assertTrue(runCatching { maximum.decide(null, false, 0, 1000) }.isFailure)
        assertTrue(runCatching { RetryPolicy { -1 }.decide(null, false, 1, 1000) }.isFailure)
    }
    @Test fun retryAfterDateSecondsInvalidAndOverflowDoNotWrap() {
        val now = 1792022400000L // 2026-10-15 00:00:00 UTC
        assertEquals(now + 3_600_000, RetryPolicy.retryAfterAt("3600", now))
        assertEquals(now + 7_200_000, RetryPolicy.retryAfterAt("Thu, 15 Oct 2026 02:00:00 GMT", now))
        assertEquals(now, RetryPolicy.retryAfterAt("Wed, 14 Oct 2026 00:00:00 GMT", now))
        assertEquals(now + 2000, RetryPolicy.retryAfterAt(" \t2\t ", now))
        for (invalid in listOf("-1", "+1", "1.0", "", "tomorrow", "Thu, 32 Oct 2026 00:00:00 GMT", "Thu, 15 Oct 2026 00:00:00 UTC", "1\r\n"))
            assertNull(invalid, RetryPolicy.retryAfterAt(invalid, now))
        assertEquals(RetryPolicy.MAX_TIME, RetryPolicy.retryAfterAt("999999999999999999999999999", now))
        assertEquals(RetryPolicy.MAX_TIME, RetryPolicy.retryAfterAt("1", RetryPolicy.MAX_TIME - 1))
        assertEquals(DeliveryDecision.Retry(now + 7_200_000), maximum.decide(429, false, 1, now, "7200"))
        assertEquals(DeliveryDecision.Retry(now + 7_200_000), maximum.decide(503, false, 1, now, "7200"))
        assertEquals(DeliveryDecision.Retry(now + 10000), maximum.decide(429, false, 1, now, "invalid"))
    }
}
