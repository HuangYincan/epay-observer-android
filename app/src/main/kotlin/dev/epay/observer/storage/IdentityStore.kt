package dev.epay.observer.storage

import dev.epay.observer.capture.*
import dev.epay.observer.parsing.*
import java.util.UUID
import java.util.concurrent.Callable

class IdentityCapacityException : IllegalStateException("identity_capacity")

class IdentityStore(private val database: ObserverDatabase, private val capacity: Int = 10_000) {
    private val dao = database.observerDao()
    init { require(capacity > 0) }

    private fun epochInTransaction(): String = dao.metadata("installation_epoch") ?: UUID.randomUUID().toString().also {
        dao.putMetadata(MetadataRow("installation_epoch", it))
    }
    fun epoch(): String = database.runInTransaction(Callable { epochInTransaction() })

    fun record(input: ObservationInput, enabled: Set<Provider>): ObservationRow = database.runInTransaction(Callable {
        require(input.key.isNotEmpty())
        val key = IdentityEncoding.identity(epochInTransaction(), input.source.user, input.source.packageName, input.key)
        val old = dao.observation(key)
        val sourceHash = IdentityEncoding.source(input.source)
        val fieldsHash = IdentityEncoding.fields(input.fields)
        if (old != null) {
            val changed = old.sourceFingerprint != sourceHash || old.fieldsFingerprint != fieldsHash ||
                old.postTime != input.postTime || old.removed
            val row = old.copy(
                sourceVersion = input.source.version, sourceFingerprint = sourceHash, fieldsFingerprint = fieldsHash,
                postTime = input.postTime, lastWallTime = input.wallTime,
                revision = if (changed && old.revision < Int.MAX_VALUE) old.revision + 1 else old.revision,
                deliveries = if (old.deliveries < Long.MAX_VALUE) old.deliveries + 1 else old.deliveries,
                everRestored = old.everRestored || input.restored, removed = false, conflict = old.conflict || changed,
            )
            dao.updateObservation(row)
            if (row.conflict) row.eventId?.let { dao.conflictEvent(it) }
            row
        } else {
            if (dao.observationCount() >= capacity) throw IdentityCapacityException()
            val snapshot = NotificationObservation(input, input.wallTime, input.elapsedTime,
                uncertain = true, everRestored = input.restored)
            val parsed = ParserRegistry.analyze(snapshot, enabled)
            val candidate = (parsed as? ParseResult.Candidate)?.takeIf { IdentityEncoding.validTime(input.wallTime) }
            val id = candidate?.let { UUID.randomUUID().toString() }
            val row = ObservationRow(key, input.source.packageName, input.source.user, input.source.version,
                sourceHash, fieldsHash, input.postTime, input.wallTime, input.elapsedTime, input.wallTime,
                1, 1, input.restored, false, false, id)
            dao.insertObservation(row)
            if (candidate != null && id != null) {
                dao.insertEvent(LocalEventRow(id, key, candidate.provider.name.lowercase(java.util.Locale.ROOT),
                    candidate.amountMinor, candidate.currency, candidate.ruleVersion, input.wallTime,
                    IdentityEncoding.localPayload(id, candidate, input.wallTime), "UNBOUND"))
            }
            row
        }
    })

    fun removed(user: Long, packageName: String, key: String) = database.runInTransaction {
        val identity = IdentityEncoding.identity(epochInTransaction(), user, packageName, key)
        dao.observation(identity)?.let { dao.updateObservation(it.copy(removed = true)) }
    }
    fun recordGap() = database.runInTransaction { dao.putMetadata(MetadataRow("ingress_gap", "true")) }
    fun stats(): StorageStats = database.runInTransaction(Callable {
        StorageStats(dao.observationCount(), dao.eventCount(), dao.conflictCount(), dao.metadata("ingress_gap") == "true")
    })
    fun event(id: String): LocalEventRow? = dao.event(id)
    fun events(): List<LocalEventRow> = dao.events()
}
