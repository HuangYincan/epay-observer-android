package dev.epay.observer.protocol

import android.os.SystemClock
import dev.epay.observer.parsing.Provider
import dev.epay.observer.storage.ObserverDatabase
import dev.epay.observer.storage.PairingStore

/** Bounded pass over Room; scheduling metadata never becomes an event queue. */
class UploadDrain(private val database: ObserverDatabase, private val delivery: OutboxDelivery,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime) {
    fun run(stopped: () -> Boolean = { false }): Long? {
        val started = elapsed()
        var attempts = 0
        while (attempts < 20 && !stopped() && elapsed() - started < 120_000) {
            var work = false
            for (provider in Provider.entries) {
                if (attempts >= 20 || stopped() || elapsed() - started >= 120_000) break
                if (delivery.sendNext(provider) != null) { attempts++; work = true }
            }
            if (!work) break
        }
        val pairing = PairingStore(database)
        return Provider.entries.mapNotNull { provider ->
            pairing.active(provider)?.takeIf(pairing::uploadAllowed)?.let {
                database.outboxDao().nextDue(it.binding.scope)
            }
        }.minOrNull()
    }
}
