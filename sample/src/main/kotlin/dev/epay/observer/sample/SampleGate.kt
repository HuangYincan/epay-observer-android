package dev.epay.observer.sample

/** The lock covers source/window checks, lazy field access and private persistence. */
class SampleGate(
    private val now: () -> Long,
    private val consumeOnce: () -> Boolean,
    private val persist: (String?, String?) -> Unit,
    private val limit: Int = 32,
) {
    init { require(limit in 1..32) }
    enum class Phase { IDLE, RUNNING, EXPIRED, STOPPED, DISCONNECTED, SOURCE_CHANGED, LIMIT, ERROR, USED }
    data class State(val phase: Phase, val count: Int, val connected: Boolean, val elapsedMs: Long)
    private var phase = Phase.IDLE
    private var connected = false
    private var began = 0L
    private var last = 0L
    private var count = 0

    @Synchronized fun connection(value: Boolean) {
        connected = value
        if (!value && phase == Phase.RUNNING) phase = Phase.DISCONNECTED
    }

    @Synchronized fun start(sourceMatches: Boolean): Boolean {
        if (phase != Phase.IDLE || !connected || !sourceMatches) return false
        try {
            if (!consumeOnce()) { phase = Phase.USED; return false }
            began = now()
            last = began
            phase = Phase.RUNNING
            return true
        } catch (_: Exception) { phase = Phase.ERROR; return false }
    }

    private fun inWindow(): Boolean {
        if (phase != Phase.RUNNING) return false
        val time = now()
        if (time < last) phase = Phase.STOPPED
        else if (time - began >= 120_000) phase = Phase.EXPIRED
        last = time
        return phase == Phase.RUNNING
    }

    @Synchronized fun posted(sourceMatches: Boolean, title: () -> CharSequence?, text: () -> CharSequence?) {
        if (!inWindow()) return
        if (!sourceMatches) { phase = Phase.SOURCE_CHANGED; return }
        try {
            if (!inWindow()) return
            val safeTitle = title()?.let { it.subSequence(0, minOf(it.length, 256)).toString() }
            if (!inWindow()) return
            val safeText = text()?.let { it.subSequence(0, minOf(it.length, 1024)).toString() }
            // A field already received just before the deadline is discarded after expiry.
            if (!inWindow()) return
            persist(safeTitle, safeText)
            count++
            if (count == limit) phase = Phase.LIMIT
        } catch (_: Exception) { phase = Phase.ERROR }
    }

    @Synchronized fun snapshot(): State {
        inWindow()
        return State(phase, count, connected, if (phase == Phase.IDLE || phase == Phase.USED) 0 else (last - began).coerceAtLeast(0))
    }

    @Synchronized fun stop() { if (phase == Phase.RUNNING) phase = Phase.STOPPED }
}
