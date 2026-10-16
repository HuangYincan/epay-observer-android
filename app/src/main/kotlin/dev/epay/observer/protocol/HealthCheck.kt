package dev.epay.observer.protocol

import android.content.Context
import dev.epay.observer.parsing.Provider
import dev.epay.observer.security.HmacKeyVault
import dev.epay.observer.security.VaultFailure
import dev.epay.observer.security.PairingRuntime
import dev.epay.observer.storage.*

enum class HealthState { BUSY, AUTHORIZED, UNBOUND, LOCAL_KEY, CLOCK, CHANGED, DENIED, RATE_LIMITED, UNAVAILABLE }

/** An explicit authorization probe: never consumes or resumes an outbox event. */
class HealthCheck(context: Context, database: ObserverDatabase,
    private val client: EventClient = EventTransport(), private val clock: () -> Long = System::currentTimeMillis,
    private val pairingBusy: () -> Boolean = { PairingRuntime.busy }) {
    private val pairing = PairingStore(database)
    private val vault = HmacKeyVault(context)
    fun check(provider: Provider): HealthState {
        if (pairingBusy()) return HealthState.BUSY
        val credential = pairing.active(provider) ?: return HealthState.UNBOUND
        val now = clock()
        if (!IdentityEncoding.validTime(now) || now < credential.notBefore || now >= credential.expiresAt) return HealthState.CLOCK
        val key = try { vault.load(credential.binding, credential.keyId) } catch (_: VaultFailure) { null }
            ?: return HealthState.LOCAL_KEY
        val result = try {
            val request = RequestSigner(clock).prepareHealth(credential.keyId, key)
            if (pairingBusy()) return HealthState.BUSY
            val response = client.send(credential.binding.origin, request)
            when {
                HealthProtocol.authorized(response) -> HealthState.AUTHORIZED
                response.status in setOf(401, 403) -> HealthState.DENIED
                response.status == 429 -> HealthState.RATE_LIMITED
                else -> HealthState.UNAVAILABLE
            }
        } catch (_: Exception) { HealthState.UNAVAILABLE }
        return if (!pairingBusy() && pairing.active(provider) == credential) result else HealthState.CHANGED
    }
}
