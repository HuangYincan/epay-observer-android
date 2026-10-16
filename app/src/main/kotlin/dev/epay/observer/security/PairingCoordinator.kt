package dev.epay.observer.security

import android.content.Context
import dev.epay.observer.parsing.Provider
import dev.epay.observer.protocol.*
import dev.epay.observer.storage.*
import java.io.File
import java.io.RandomAccessFile

class PairingConfirmation internal constructor(val origin: String, internal val token: String,
    internal val scopeBytes: ByteArray, val scope: PairingScope) {
    override fun toString() = "PairingConfirmation(redacted)"
}

/** Must run off the main thread. The file lock covers the whole remote/local
 * transition so two UI actions or processes cannot replace a pending enrollment. */
class PairingCoordinator(context: Context, database: ObserverDatabase,
    private val transport: PairingClient = PairingTransport(), namespace: String = "pairing") {
    private val app = context.applicationContext
    private val journal = PairingJournal(app, namespace)
    private val vault = HmacKeyVault(app)
    private val store = PairingStore(database)
    private val lock = File(app.noBackupFilesDir, "observer-$namespace.workflow.lock")

    fun inspect(origin: String, token: String): PairingConfirmation {
        val bytes = transport.post(origin, token, "inspect", PairingProtocol.inspectBody())
        val scope = PairingProtocol.scope(bytes, origin)
        require(PairingPolicy.permits(scope.sourceClass)) { "environment_denied" }
        require(scope.expiresAt > System.currentTimeMillis()) { "invalid_grant" }
        if (scope.mode == "rotate") {
            val current = store.active(scope.provider) ?: throw PairingFailure("binding_changed")
            require(current.binding.origin == origin && current.binding.environment == scope.environment &&
                current.binding.receiverId == scope.receiverId && current.binding.deviceId == scope.deviceId &&
                current.binding.version == scope.expectedVersion) { "binding_changed" }
        }
        return PairingConfirmation(origin, token, bytes, scope)
    }
    fun confirm(confirmation: PairingConfirmation): ActiveCredential = locked {
        check(journal.read() == null) { "pairing_pending_exists" }
        require(confirmation.scope.expiresAt > System.currentTimeMillis()) { "invalid_grant" }
        val pending = PendingPairing(confirmation.origin, confirmation.token, confirmation.scopeBytes.copyOf(), PairingProtocol.newRequest())
        journal.save(pending) // Must commit before any redeem request.
        complete(pending)
    }
    fun recover(): ActiveCredential? = locked {
        val pending = try { journal.read() } catch (failure: Exception) {
            Provider.entries.forEach(store::pause)
            throw failure
        }
        pending?.let(::complete)
    }
    fun active(): Map<Provider, ActiveCredential> = Provider.entries.mapNotNull { provider ->
        store.active(provider)?.let { current ->
            if (PairingPolicy.permits(current.binding.sourceClass) && current.expiresAt > System.currentTimeMillis() &&
                vault.load(current.binding, current.keyId) != null) provider to current
            else { store.revoke(provider); null }
        }
    }.toMap()
    fun forgetPending() = locked {
        // Local cancellation does not claim remote revocation. A consumed grant
        // must be revoked on the server; next replacement uses a new generation.
        val pending = try { journal.read() } catch (_: Exception) {
            Provider.entries.forEach(store::revoke)
            null
        }
        pending?.let { store.revoke(it.scope.provider) }
        journal.clear()
    }
    private fun complete(pending: PendingPairing): ActiveCredential {
        val scope = pending.scope
        require(PairingPolicy.permits(scope.sourceClass)) { "environment_denied" }
        val current = store.active(scope.provider)
        if (current?.enrollmentId == pending.request.enrollmentId && vault.load(current.binding, current.keyId) != null) {
            journal.clear(); return current // Room committed; journal cleanup had failed.
        }
        store.pause(scope.provider)
        if (scope.expiresAt <= System.currentTimeMillis()) throw PairingFailure("invalid_grant")
        val bytes = transport.post(pending.origin, pending.token, "redeem", pending.request.bytes())
        val enrollment = try { PairingProtocol.enrollment(bytes, scope, pending.request) } finally { bytes.fill(0) }
        try {
            // Wait for actual local validity, without backdating not_before.
            val wait = enrollment.notBefore - System.currentTimeMillis()
            if (wait in 1..5000) Thread.sleep(wait)
            val now = System.currentTimeMillis()
            require(enrollment.notBefore <= now && now < enrollment.expiresAt) { "clock_error" }
            vault.install(enrollment.binding, enrollment.keyId, enrollment.material)
            val activated = transport.post(pending.origin, pending.token, "activate", pending.request.bytes())
            PairingProtocol.activation(activated, enrollment)
            store.activate(enrollment)
            val result = store.active(scope.provider) ?: throw PairingFailure("storage")
            journal.clear()
            return result
        } finally { enrollment.material.fill(0) }
    }
    private fun <T> locked(action: () -> T): T = synchronized(processLock) {
        RandomAccessFile(lock, "rw").use { file -> file.channel.lock().use { action() } }
    }
    companion object { private val processLock = Any() }
}
