package dev.epay.observer.capture

import java.util.concurrent.CopyOnWriteArraySet

/** Process-local state, deliberately not a receipt queue. Contents never leave the device. */
object CaptureState {
    val journal = ObservationJournal()
    @Volatile var connected = false
        private set
    @Volatile var rejected = 0
        private set
    @Volatile var error: String? = null
        private set
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    fun subscribe(listener: () -> Unit) { listeners.add(listener) }
    fun unsubscribe(listener: () -> Unit) { listeners.remove(listener) }
    fun changed() { listeners.forEach { it() } }
    fun connection(value: Boolean) { connected = value; if (value) error = null; changed() }
    fun reject() { rejected++; changed() }
    fun failure(reason: String) { error = reason; changed() }
}
