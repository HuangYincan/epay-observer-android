package dev.epay.observer.storage

import android.content.Context
import androidx.room.*

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

data class StorageStats(val observations: Int, val events: Int, val conflicts: Int, val gap: Boolean)

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
    @Query("SELECT COUNT(*) FROM local_events") fun eventCount(): Int
    @Query("SELECT COUNT(*) FROM local_events WHERE state = 'IDENTITY_CONFLICT'") fun conflictCount(): Int
}

@Database(entities = [MetadataRow::class, ObservationRow::class, LocalEventRow::class], version = 1, exportSchema = true)
abstract class ObserverDatabase : RoomDatabase() {
    abstract fun observerDao(): ObserverDao
    companion object {
        fun open(context: Context, name: String = "observer.db"): ObserverDatabase =
            Room.databaseBuilder(context.applicationContext, ObserverDatabase::class.java, name)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build() // No destructive migration fallback; no main-thread queries.
    }
}
