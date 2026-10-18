package dev.epay.observer.sample

import org.junit.Assert.*
import org.junit.Test

class SampleTest {
    private var time = 100L
    private var used = false
    private var reads = 0
    private val saved = mutableListOf<Pair<String?, String?>>()
    private fun gate() = SampleGate({ time }, { if (used) false else { used = true; true } },
        { a, b -> saved.add(a to b) })
    private fun post(gate: SampleGate, matches: Boolean = true) =
        gate.posted(matches, { reads++; "title" }, { reads++; "text" })
    private fun start(gate: SampleGate) { gate.connection(true); assertTrue(gate.start(true)) }

    @Test fun requiresConnectedSourceAndSingleUseAcrossProcesses() {
        val a = gate()
        assertFalse(a.start(true)); a.connection(true); assertFalse(a.start(false))
        assertFalse(used); assertTrue(a.start(true)); a.stop()
        assertFalse(a.start(true))
        val b = gate(); b.connection(true); assertFalse(b.start(true))
        post(b); assertEquals(0, reads)
        assertEquals(SampleGate.Phase.USED, b.snapshot().phase)
    }
    @Test fun neverReadBeforeStartOrAtDeadline() {
        val a = gate(); post(a); assertEquals(0, reads); start(a)
        time += 120_000; post(a); assertEquals(0, reads)
        assertEquals(SampleGate.Phase.EXPIRED, a.snapshot().phase)
    }
    @Test fun deadlineBetweenFieldsPreventsBodyReadAndPersistence() {
        val a = gate(); start(a)
        a.posted(true, { reads++; time += 120_000; "sensitive" }, { reads++; "never" })
        assertEquals(1, reads); assertTrue(saved.isEmpty())
    }
    @Test fun sourceMismatchStopsBeforeAnyContent() {
        val a = gate(); start(a); post(a, false); post(a)
        assertEquals(0, reads); assertEquals(SampleGate.Phase.SOURCE_CHANGED, a.snapshot().phase)
    }
    @Test fun disconnectOrStopCannotResume() {
        val a = gate(); start(a); a.connection(false); a.connection(true)
        post(a); assertFalse(a.start(true)); assertEquals(0, reads)
    }
    @Test fun backwardsClockStopsContent() {
        val a = gate(); start(a); time--; post(a)
        assertEquals(0, reads); assertEquals(SampleGate.Phase.STOPPED, a.snapshot().phase)
    }
    @Test fun capacityAndLengthsBoundRawStorage() {
        val a = gate(); start(a)
        a.posted(true, { "a".repeat(300) }, { "b".repeat(2000) })
        repeat(31) { post(a) }
        val before = reads; post(a)
        assertEquals(before, reads); assertEquals(32, saved.size)
        assertEquals(256, saved[0].first!!.length); assertEquals(1024, saved[0].second!!.length)
        assertEquals(SampleGate.Phase.LIMIT, a.snapshot().phase)
    }
    @Test fun receiptTemplateLimitStopsBeforeAnyThirdFieldRead() {
        val a = SampleGate({ time }, { true }, { title, text -> saved.add(title to text) }, limit = 2)
        start(a); post(a); post(a)
        assertEquals(2, saved.size)
        assertEquals(SampleGate.Phase.LIMIT, a.snapshot().phase)
        val before = reads
        post(a); a.connection(false); a.connection(true); post(a)
        assertEquals(before, reads)
        assertFalse(a.start(true))
    }
    @Test fun contentOrStorageErrorStopsWithoutLeakingException() {
        val a = gate(); start(a)
        a.posted(true, { throw IllegalArgumentException("private") }, { reads++; "never" })
        post(a); assertEquals(0, reads); assertEquals(SampleGate.Phase.ERROR, a.snapshot().phase)
        val b = SampleGate({ time }, { true }, { _, _ -> throw java.io.IOException("private") })
        start(b); post(b); val before = reads; post(b)
        assertEquals(before, reads); assertEquals(SampleGate.Phase.ERROR, b.snapshot().phase)
    }
    @Test fun markerFailureDoesNotStart() {
        val a = SampleGate({ time }, { throw java.io.IOException() }, { _, _ -> fail() })
        a.connection(true); assertFalse(a.start(true)); post(a); assertEquals(0, reads)
    }
    @Test fun pinsRejectEveryEnvelopeAndInstallationMismatch() {
        fun accepts(pkg: String = AlipaySource.PACKAGE, op: String = pkg, user: Boolean = true,
                    uid: Boolean = true, version: Long = AlipaySource.VERSION,
                    certs: Set<String> = setOf(AlipaySource.CERTIFICATE)) =
            AlipaySource.accepts(pkg, op, user, uid, version, certs)
        assertTrue(accepts()); assertFalse(accepts(pkg = "fake")); assertFalse(accepts(op = "fake"))
        assertFalse(accepts(user = false)); assertFalse(accepts(uid = false))
        assertFalse(accepts(version = 1)); assertFalse(accepts(certs = emptySet()))
        assertFalse(accepts(certs = setOf(AlipaySource.CERTIFICATE, "other")))
    }
}
