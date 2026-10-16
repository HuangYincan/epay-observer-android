package dev.epay.observer.storage

import androidx.room.*

@Entity(tableName = "outbox", indices = [Index(value = ["scope", "state", "nextAttemptAt"])],
    foreignKeys = [ForeignKey(entity = LocalEventRow::class, parentColumns = ["eventId"],
        childColumns = ["eventId"], onDelete = ForeignKey.RESTRICT)])
data class OutboxRow(
    @PrimaryKey val eventId: String, val scope: String, val provider: String,
    val origin: String, val environment: String, val body: ByteArray,
    val state: String, val attempts: Long, val nextAttemptAt: Long,
    val leaseToken: String?, val leaseUntil: Long?, val receivedAt: Long?, val reason: String?,
)

@Dao
interface OutboxDao {
    @Insert fun insert(row: OutboxRow)
    @Query("SELECT * FROM outbox ORDER BY eventId") fun rows(): List<OutboxRow>
    @Query("SELECT COUNT(*) FROM outbox") fun count(): Int
    @Query("SELECT COALESCE(SUM(length(body)), 0) FROM outbox") fun payloadBytes(): Long
    @Query("SELECT * FROM outbox WHERE scope = :scope AND ((state IN ('READY','RETRY') AND nextAttemptAt <= :now) OR (state = 'IN_FLIGHT' AND leaseUntil <= :now)) ORDER BY nextAttemptAt, eventId LIMIT 1")
    fun eligible(scope: String, now: Long): OutboxRow?
    @Query("UPDATE outbox SET state = 'IN_FLIGHT', leaseToken = :token, leaseUntil = :until, attempts = attempts + 1, reason = NULL WHERE eventId = :id")
    fun lease(id: String, token: String, until: Long)
    @Query("UPDATE outbox SET state = 'ACKED', receivedAt = :receivedAt, leaseToken = NULL, leaseUntil = NULL, reason = NULL WHERE eventId = :id AND state = 'IN_FLIGHT' AND leaseToken = :token")
    fun ack(id: String, token: String, receivedAt: Long): Int
    @Query("UPDATE outbox SET state = 'RETRY', nextAttemptAt = :next, leaseToken = NULL, leaseUntil = NULL WHERE eventId = :id AND state = 'IN_FLIGHT' AND leaseToken = :token")
    fun retry(id: String, token: String, next: Long): Int
    @Query("UPDATE outbox SET state = :state, reason = :reason, leaseToken = NULL, leaseUntil = NULL WHERE eventId = :id AND state = 'IN_FLIGHT' AND leaseToken = :token")
    fun finish(id: String, token: String, state: String, reason: String): Int
    @Query("UPDATE outbox SET state = 'HELD', reason = :reason, leaseToken = NULL, leaseUntil = NULL WHERE eventId = :id AND state IN ('READY','IN_FLIGHT','RETRY')")
    fun holdEvent(id: String, reason: String)
    @Query("UPDATE outbox SET state = 'HELD', reason = 'BINDING_CHANGED', leaseToken = NULL, leaseUntil = NULL WHERE provider = :provider AND scope != :scope AND state IN ('READY','IN_FLIGHT','RETRY')")
    fun holdOtherScopes(provider: String, scope: String)
    @Query("UPDATE outbox SET state = 'HELD', reason = 'BINDING_REVOKED', leaseToken = NULL, leaseUntil = NULL WHERE provider = :provider AND state IN ('READY','IN_FLIGHT','RETRY')")
    fun holdProvider(provider: String)
}
