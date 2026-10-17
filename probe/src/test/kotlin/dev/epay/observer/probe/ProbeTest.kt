package dev.epay.observer.probe

import org.junit.Assert.*
import org.junit.Test

class ProbeTest {
    @Test fun pinsEverySourceBoundary() {
        fun accept(pkg: String = AlipaySource.PACKAGE, op: String = AlipaySource.PACKAGE,
                   user: Boolean = true, uid: Boolean = true, version: Long = AlipaySource.VERSION,
                   certs: Set<String> = setOf(AlipaySource.CERTIFICATE)) =
            AlipaySource.accepts(pkg, op, user, uid, version, certs)
        assertTrue(accept())
        assertFalse(accept(pkg = "dev.epay.observer.fixture"))
        assertFalse(accept(op = "android"))
        assertFalse(accept(user = false))
        assertFalse(accept(uid = false))
        assertFalse(accept(version = AlipaySource.VERSION + 1))
        assertFalse(accept(certs = emptySet()))
        assertFalse(accept(certs = setOf("untrusted")))
        assertFalse(accept(certs = setOf(AlipaySource.CERTIFICATE, "another-signer")))
    }

    @Test fun defaultStoppedAndExplicitStartRequiresConnectionAndSource() {
        val w = ProbeWindow { 1_000L }
        w.posted(true)
        assertEquals(0, w.snapshot().callbacks)
        assertFalse(w.start(true))
        w.connection(true)
        assertFalse(w.start(false))
        assertEquals(ProbeWindow.Phase.IDLE, w.snapshot().phase)
        assertTrue(w.start(true))
        assertEquals(120L, w.snapshot().remainingSeconds)
    }

    @Test fun exactDeadlineStopsAndNeverCountsLateCallbacks() {
        var now = 1000L
        val w = ProbeWindow { now }
        w.connection(true); w.start(true)
        now += ProbeWindow.WINDOW_MS - 1
        w.posted(true)
        assertEquals(1, w.snapshot().callbacks)
        now++
        w.posted(true)
        assertEquals(1, w.snapshot().callbacks)
        assertEquals(ProbeWindow.Phase.EXPIRED, w.snapshot().phase)
        assertEquals(0L, w.snapshot().remainingSeconds)
    }

    @Test fun disconnectDoesNotResumeAndEachStartResetsCounts() {
        val w = ProbeWindow { 100L }
        w.connection(true); w.start(true); w.posted(true)
        w.connection(false); w.connection(true); w.posted(true)
        assertEquals(ProbeWindow.Phase.DISCONNECTED, w.snapshot().phase)
        assertEquals(1, w.snapshot().callbacks)
        w.start(true)
        assertEquals(0, w.snapshot().callbacks)
        w.stop(); w.posted(true)
        assertEquals(ProbeWindow.Phase.STOPPED, w.snapshot().phase)
        assertEquals(0, w.snapshot().callbacks)
    }

    @Test fun sourceChangeAndClockRollbackStopWindow() {
        var now = 10_000L
        val w = ProbeWindow { now }
        w.connection(true); w.start(true); w.posted(false); w.posted(true)
        assertEquals(ProbeWindow.Phase.SOURCE_CHANGED, w.snapshot().phase)
        assertEquals(0, w.snapshot().callbacks)
        w.start(true); now--; w.posted(true)
        assertEquals(ProbeWindow.Phase.CLOCK_ERROR, w.snapshot().phase)
        assertEquals(0, w.snapshot().callbacks)
    }

    @Test fun capacityIsBoundedEvenForRepeatedCallbacks() {
        val w = ProbeWindow { 0L }
        w.connection(true); w.start(true)
        repeat(ProbeWindow.MAX_CALLBACKS + 100) { w.posted(true) }
        assertEquals(ProbeWindow.MAX_CALLBACKS, w.snapshot().callbacks)
        assertEquals(ProbeWindow.Phase.CAPACITY, w.snapshot().phase)
    }
}
