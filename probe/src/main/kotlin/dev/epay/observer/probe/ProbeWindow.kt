package dev.epay.observer.probe

/** Only aggregate counts; no notification content, key, account or disk state. */
class ProbeWindow(private val clock: () -> Long) {
    enum class Phase { IDLE, RUNNING, STOPPED, EXPIRED, DISCONNECTED, SOURCE_CHANGED, CAPACITY, CLOCK_ERROR }
    data class Snapshot(val connected: Boolean, val phase: Phase, val callbacks: Int, val remainingSeconds: Long)

    private var connected = false
    private var phase = Phase.IDLE
    private var started = 0L
    private var callbacks = 0

    @Synchronized fun connection(value: Boolean) {
        connected = value
        if (!value && phase == Phase.RUNNING) phase = Phase.DISCONNECTED
    }

    @Synchronized fun start(sourceMatches: Boolean): Boolean {
        if (!connected || !sourceMatches) return false
        started = clock()
        callbacks = 0
        phase = Phase.RUNNING
        return true
    }

    @Synchronized fun stop() { if (phase == Phase.RUNNING) phase = Phase.STOPPED }

    @Synchronized fun posted(sourceMatches: Boolean) {
        expire()
        if (phase != Phase.RUNNING) return
        if (!sourceMatches) { phase = Phase.SOURCE_CHANGED; return }
        callbacks++
        if (callbacks >= MAX_CALLBACKS) phase = Phase.CAPACITY
    }

    @Synchronized fun snapshot(): Snapshot {
        val elapsed = expire()
        return Snapshot(connected, phase, callbacks,
            if (phase == Phase.RUNNING) (WINDOW_MS - elapsed + 999) / 1000 else 0)
    }

    private fun expire(): Long {
        val elapsed = clock() - started
        if (phase == Phase.RUNNING) {
            if (elapsed < 0) phase = Phase.CLOCK_ERROR
            else if (elapsed >= WINDOW_MS) phase = Phase.EXPIRED
        }
        return elapsed
    }

    companion object {
        const val WINDOW_MS = 120_000L
        const val MAX_CALLBACKS = 500
    }
}
