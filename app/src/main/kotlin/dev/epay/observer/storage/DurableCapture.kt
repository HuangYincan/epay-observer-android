package dev.epay.observer.storage

import android.app.Application
import android.os.Handler
import android.os.Looper
import dev.epay.observer.capture.*
import dev.epay.observer.parsing.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** Serial, bounded handoff; only committed database snapshots are published as durable state. */
object DurableCapture {
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(128),
        { task -> Thread(task, "observer-persistence") }, ThreadPoolExecutor.AbortPolicy())
    private val main = Handler(Looper.getMainLooper())
    private val gap = AtomicBoolean(false)
    private lateinit var application: Application
    private var store: IdentityStore? = null // Worker-confined.
    @Volatile var enabledProviders: Set<Provider> = Provider.entries.toSet()
        private set
    @Volatile var stats = StorageStats(0, 0, 0, false)
        private set
    @Volatile var error: String? = null
        private set

    fun start(application: Application) {
        this.application = application
        enabledProviders = ParserPreferences(application).enabled()
        submit { }
    }
    fun providerChanged(provider: Provider, enabled: Boolean) {
        enabledProviders = if (enabled) enabledProviders + provider else enabledProviders - provider
    }
    fun accept(input: ObservationInput) {
        val configuration = enabledProviders.toSet()
        submit { it.record(input, configuration) }
    }
    fun removed(user: Long, packageName: String, key: String) { submit { it.removed(user, packageName, key) } }
    private fun publish(value: StorageStats?, failure: String?) {
        main.post {
            if (value != null) stats = value
            error = failure ?: if (gap.get()) "ingress_gap" else null
            CaptureState.changed()
        }
    }
    private fun submit(action: (IdentityStore) -> Unit) {
        try {
            worker.execute {
                try {
                    val current = store ?: IdentityStore(ObserverDatabase.open(application)).also { store = it }
                    if (gap.getAndSet(false)) {
                        try { current.recordGap() } catch (failure: RuntimeException) { gap.set(true); throw failure }
                    }
                    action(current)
                    publish(current.stats(), null)
                } catch (_: IdentityCapacityException) {
                    gap.set(true)
                    publish(null, "capacity")
                } catch (_: RuntimeException) {
                    gap.set(true)
                    // No raw SQL, paths, source data or exception messages in diagnostics.
                    publish(null, "database")
                }
            }
        } catch (_: RejectedExecutionException) {
            gap.set(true)
            publish(null, "ingress_gap")
        }
    }
    internal fun awaitIdle(timeoutSeconds: Long = 15) {
        worker.submit { }.get(timeoutSeconds, TimeUnit.SECONDS)
    }
}
