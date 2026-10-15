package dev.epay.observer

import dev.epay.observer.capture.*
import org.junit.Assert.*
import org.junit.Test

class ObservationJournalTest {
    private val source = SourceIdentity("test.source", 0, 1, setOf("certificate"))
    private val fields = NotificationFields("合成", "收款 10.00 元", null, "test", "status", 0, null)
    private fun input(key: String = "a", post: Long = 1, wall: Long = 2, restored: Boolean = false) =
        ObservationInput(key, source, post, wall, wall, restored, fields)

    @Test fun duplicateAndUpdateKeepIdentityAndFirstTime() {
        val journal = ObservationJournal()
        repeat(20) { journal.record(input()) }
        assertEquals(1, journal.snapshots().size)
        assertEquals(1, journal.snapshots().single().revision)
        assertEquals(20, journal.snapshots().single().deliveries)
        journal.record(input(post = 5, wall = 6).copy(fields = fields.copy(text = "收款 20.00 元")))
        val record = journal.snapshots().single()
        assertEquals(2, record.revision)
        assertEquals(2L, record.firstWallTime)
        assertTrue(record.uncertain)
    }
    @Test fun identicalTextDifferentKeysRemainSeparate() {
        val journal = ObservationJournal()
        journal.record(input("a")); journal.record(input("b"))
        assertEquals(2, journal.snapshots().size)
    }
    @Test fun restorationRetainsOriginalTimeAndUnknownRemainsUncertain() {
        val journal = ObservationJournal()
        journal.record(input())
        journal.record(input(wall = 100, restored = true))
        journal.record(input("new", wall = 200, restored = true))
        assertEquals(2L, journal.snapshots().first().firstWallTime)
        assertTrue(journal.snapshots().all { it.everRestored && it.uncertain })
    }
    @Test fun capacityDoesNotEvictOrRecreateAndRemovalDoesNotDeleteHistory() {
        val journal = ObservationJournal(1)
        journal.record(input()); journal.record(input("b"))
        assertEquals(1, journal.overflow)
        journal.removed(0, "test.source", "unknown")
        journal.removed(0, "test.source", "a")
        assertTrue(journal.snapshots().single().removed)
        journal.record(input(post = 8))
        assertFalse(journal.snapshots().single().removed)
        assertEquals(2L, journal.snapshots().single().firstWallTime)
    }
    @Test fun allSourceDimensionsAreRequired() {
        fun allows(pkg: String = "expected", op: String = "expected", user: Long = 0,
                   cert: Set<String> = setOf("same"), expected: Set<String> = setOf("same")) =
            SourceBoundary.allows(pkg, op, user, 0, "expected", cert, expected)
        assertTrue(allows())
        assertFalse(allows(pkg = "unrelated"))
        assertFalse(allows(op = "delegate"))
        assertFalse(allows(user = 10))
        assertFalse(allows(cert = emptySet(), expected = emptySet()))
        assertFalse(allows(cert = setOf("other")))
        assertFalse(allows(cert = setOf("same", "other")))
    }
}
