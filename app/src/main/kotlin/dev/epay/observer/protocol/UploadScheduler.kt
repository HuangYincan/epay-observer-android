package dev.epay.observer.protocol

import android.content.Context
import androidx.work.*
import dev.epay.observer.security.PairingRuntime
import dev.epay.observer.storage.ObserverDatabase
import java.util.concurrent.TimeUnit

/** Only wake-up hints live in WorkManager. Room owns bodies, attempts and due times. */
object UploadScheduler {
    const val IMMEDIATE = "observer-upload-now"
    const val WAKE = "observer-upload-wake"
    const val RECONCILE = "observer-upload-reconcile"
    private val network = UploadNetworkPolicy.constraints()
    private val planningLock = Any()

    fun initialize(context: Context) {
        WorkManager.initialize(context, Configuration.Builder().setMinimumLoggingLevel(android.util.Log.ERROR).build())
    }
    fun reconcile(context: Context): Operation = WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        RECONCILE, ExistingPeriodicWorkPolicy.KEEP,
        PeriodicWorkRequestBuilder<UploadWorker>(15, TimeUnit.MINUTES).setConstraints(network).build())
    fun kick(context: Context): Operation = WorkManager.getInstance(context).enqueueUniqueWork(
        IMMEDIATE, ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<UploadWorker>().setConstraints(network).build())
    fun wake(context: Context, due: Long, now: Long = System.currentTimeMillis()): Operation =
        WorkManager.getInstance(context).enqueueUniqueWork(WAKE, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<UploadWakeWorker>()
                .setInitialDelay((due - now).coerceAtLeast(1_000), TimeUnit.MILLISECONDS).build())
    /** Re-read under the planning lock: an older worker's snapshot cannot move
     * another worker's earlier retry into the future. Never accepts a cached due. */
    fun replan(context: Context, database: ObserverDatabase, clock: () -> Long = System::currentTimeMillis) = synchronized(planningLock) {
        UploadDrain.nextDue(database)?.let { wake(context, it, clock()).result.get() }
        Unit
    }
}

class UploadWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        if (PairingRuntime.busy || isStopped) return Result.retry()
        return try {
            val database = ObserverDatabase.open(applicationContext)
            try {
                UploadDrain(database, OutboxDelivery(applicationContext, database)).run { isStopped }
                if (!isStopped) UploadScheduler.replan(applicationContext, database)
            } finally { database.close() }
            UploadRuntime.refresh()
            Result.success()
        } catch (_: Exception) {
            UploadRuntime.failed()
            Result.retry()
        }
    }
}

/** Replacing a timer must never cancel an active HTTP upload. */
class UploadWakeWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result = try {
        UploadScheduler.kick(applicationContext).result.get()
        Result.success()
    } catch (_: Exception) { Result.retry() }
}
