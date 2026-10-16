package dev.epay.observer.storage

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "metadata")
data class MetadataRow(@PrimaryKey val name: String, val value: String)

@Entity(tableName = "observation_identities")
data class ObservationRow(
    @PrimaryKey val identityKey: String,
    val sourcePackage: String, val userSerial: Long, val sourceVersion: Long,
    val sourceFingerprint: String, val fieldsFingerprint: String,
    val postTime: Long, val firstWallTime: Long, val firstElapsedTime: Long,
    val lastWallTime: Long, val revision: Int, val deliveries: Long,
    val everRestored: Boolean, val removed: Boolean, val conflict: Boolean,
    val eventId: String?,
)

@Entity(tableName = "local_events", indices = [Index(value = ["identityKey"], unique = true)],
    foreignKeys = [ForeignKey(entity = ObservationRow::class, parentColumns = ["identityKey"],
        childColumns = ["identityKey"], onDelete = ForeignKey.RESTRICT)])
data class LocalEventRow(
    @PrimaryKey val eventId: String, val identityKey: String,
    val provider: String, val amountMinor: Long, val currency: String, val parserVersion: String,
    val observedAt: Long, val payload: ByteArray, val state: String,
)

data class StorageStats(val observations: Int, val events: Int, val conflicts: Int, val gap: Boolean, val capacityBlocked: Boolean = false)

@Dao
interface ObserverDao {
    @Query("SELECT value FROM metadata WHERE name = :name") fun metadata(name: String): String?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putMetadata(row: MetadataRow)
    @Query("SELECT * FROM observation_identities WHERE identityKey = :key") fun observation(key: String): ObservationRow?
    @Insert fun insertObservation(row: ObservationRow)
    @Update fun updateObservation(row: ObservationRow)
    @Insert fun insertEvent(row: LocalEventRow)
    @Query("UPDATE local_events SET state = 'IDENTITY_CONFLICT' WHERE eventId = :id") fun conflictEvent(id: String)
    @Query("SELECT * FROM local_events WHERE eventId = :id") fun event(id: String): LocalEventRow?
    @Query("SELECT * FROM local_events ORDER BY eventId") fun events(): List<LocalEventRow>
    @Query("SELECT COUNT(*) FROM observation_identities") fun observationCount(): Int
    @Query("SELECT COALESCE(SUM(length(payload)), 0) FROM local_events") fun payloadBytes(): Long
    @Query("SELECT COUNT(*) FROM local_events") fun eventCount(): Int
    @Query("SELECT COUNT(*) FROM local_events WHERE state = 'IDENTITY_CONFLICT'") fun conflictCount(): Int
}

@Database(entities = [MetadataRow::class, ObservationRow::class, LocalEventRow::class, OutboxRow::class], version = 2, exportSchema = true)
abstract class ObserverDatabase : RoomDatabase() {
    abstract fun observerDao(): ObserverDao
    abstract fun outboxDao(): OutboxDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS outbox (eventId TEXT NOT NULL, scope TEXT NOT NULL, provider TEXT NOT NULL, origin TEXT NOT NULL, environment TEXT NOT NULL, body BLOB NOT NULL, state TEXT NOT NULL, attempts INTEGER NOT NULL, nextAttemptAt INTEGER NOT NULL, leaseToken TEXT, leaseUntil INTEGER, receivedAt INTEGER, reason TEXT, PRIMARY KEY(eventId), FOREIGN KEY(eventId) REFERENCES local_events(eventId) ON UPDATE NO ACTION ON DELETE RESTRICT)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_outbox_scope_state_nextAttemptAt ON outbox (scope, state, nextAttemptAt)")
            }
        }
        fun open(context: Context, name: String = "observer.db"): ObserverDatabase =
            Room.databaseBuilder(context.applicationContext, ObserverDatabase::class.java, name)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2)
                .build() // No destructive migration fallback; no main-thread queries.
    }
}
