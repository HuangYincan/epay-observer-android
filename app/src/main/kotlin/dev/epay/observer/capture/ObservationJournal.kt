package dev.epay.observer.capture

/** Diagnostic snapshots only. No payment/event identity or persistent delivery semantics. */
data class SourceIdentity(val packageName: String, val user: Long, val version: Long, val signers: Set<String>)
data class NotificationFields(
    val title: String?, val text: String?, val bigText: String?,
    val channel: String?, val category: String?, val flags: Int, val group: String?,
    val unsupported: Boolean = false,
)
data class ObservationInput(
    val key: String, val source: SourceIdentity, val postTime: Long,
    val wallTime: Long, val elapsedTime: Long, val restored: Boolean,
    val fields: NotificationFields,
)
data class NotificationObservation(
    val latest: ObservationInput, val firstWallTime: Long, val firstElapsedTime: Long,
    val revision: Int = 1, val deliveries: Int = 1, val uncertain: Boolean,
    val everRestored: Boolean, val removed: Boolean = false,
)

class ObservationJournal(private val capacity: Int = 100) {
    val epoch: String = java.util.UUID.randomUUID().toString()
    private val records = linkedMapOf<Triple<Long, String, String>, NotificationObservation>()
    var overflow: Int = 0
        private set

    @Synchronized fun record(input: ObservationInput) {
        if (input.key.isEmpty()) return
        val identity = Triple(input.source.user, input.source.packageName, input.key)
        val previous = records[identity]
        if (previous == null) {
            if (records.size >= capacity) { overflow++; return }
            records[identity] = NotificationObservation(
                input, input.wallTime, input.elapsedTime,
                uncertain = true, everRestored = input.restored,
            )
        } else {
            val changed = previous.latest.fields != input.fields ||
                previous.latest.postTime != input.postTime || previous.latest.source != input.source
            records[identity] = previous.copy(
                latest = input, revision = previous.revision + if (changed) 1 else 0,
                deliveries = previous.deliveries + 1, uncertain = true,
                everRestored = previous.everRestored || input.restored, removed = false,
            )
        }
    }

    @Synchronized fun removed(user: Long, packageName: String, key: String) {
        val identity = Triple(user, packageName, key)
        records[identity]?.let { records[identity] = it.copy(removed = true) }
    }

    @Synchronized fun snapshots(): List<NotificationObservation> = records.values.toList()
}

/** Evaluated before extracting any notification extras. No package-only trust. */
object SourceBoundary {
    fun allows(packageName: String, opPackage: String, user: Long, currentUser: Long,
               expectedPackage: String, signers: Set<String>, expectedSigners: Set<String>): Boolean =
        packageName == expectedPackage && opPackage == packageName && user == currentUser &&
            signers.isNotEmpty() && signers == expectedSigners
}
