package dev.epay.observer.security

import android.content.Context
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import dev.epay.observer.storage.BindingSnapshot
import dev.epay.observer.storage.IdentityEncoding
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

enum class VaultFailureKind { INVALID_INPUT, KEY_CONFLICT, UNAVAILABLE }
class VaultFailure(val kind: VaultFailureKind) : IllegalStateException(kind.name)
enum class KeyInstallation { CREATED, ALREADY_PRESENT }

/** HMAC import only. Pairing, binding activation, rotation policy and network I/O live elsewhere. */
class HmacKeyVault(context: Context) {
    private val lockFile = File(context.applicationContext.noBackupFilesDir, "observer-vault.lock")

    /** Takes ownership of material: always zeroes the supplied array, including on failure. */
    fun install(binding: BindingSnapshot, keyId: String, material: ByteArray): KeyInstallation {
        try {
            if (material.size != 32) throw VaultFailure(VaultFailureKind.INVALID_INPUT)
            val alias = alias(binding, keyId)
            return locked { store ->
                val incoming = SecretKeySpec(material, KeyProperties.KEY_ALGORITHM_HMAC_SHA256)
                val existing = store.getKey(alias, null) as? SecretKey
                if (existing != null) {
                    val challenge = ("epay-observer-key-import-v1:" + alias).toByteArray(Charsets.UTF_8)
                    if (!MessageDigest.isEqual(mac(existing, challenge), mac(incoming, challenge))) {
                        throw VaultFailure(VaultFailureKind.KEY_CONFLICT)
                    }
                    KeyInstallation.ALREADY_PRESENT
                } else {
                    // An unexpected non-secret-key entry is not ours to overwrite.
                    if (store.containsAlias(alias)) throw VaultFailure(VaultFailureKind.KEY_CONFLICT)
                    val protection = KeyProtection.Builder(KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setUserAuthenticationRequired(false)
                        .build()
                    store.setEntry(alias, KeyStore.SecretKeyEntry(incoming), protection)
                    KeyInstallation.CREATED
                }
            }
        } finally {
            material.fill(0)
        }
    }

    /** Missing keys are not regenerated or recovered from plaintext preferences. */
    fun load(binding: BindingSnapshot, keyId: String): SecretKey? {
        val alias = alias(binding, keyId)
        return locked { it.getKey(alias, null) as? SecretKey }
    }

    /** Caller must pause bound work before local removal; server revocation is a separate action. */
    fun remove(binding: BindingSnapshot, keyId: String) {
        val alias = alias(binding, keyId)
        locked { it.deleteEntry(alias) }
    }

    private fun alias(binding: BindingSnapshot, keyId: String): String {
        if (!Regex("[A-Za-z0-9_-]{1,64}").matches(keyId)) throw VaultFailure(VaultFailureKind.INVALID_INPUT)
        return "observer.hmac.v1." + IdentityEncoding.digest("credential-v1", binding.scope, keyId)
    }

    // The JVM mutex avoids overlapping-lock exceptions, while the app-private file
    // lock serializes separate processes and is released automatically on death.
    private fun <T> locked(action: (KeyStore) -> T): T = synchronized(processLock) {
        try {
            RandomAccessFile(lockFile, "rw").use { file ->
                file.channel.lock().use {
                    action(KeyStore.getInstance("AndroidKeyStore").apply { load(null) })
                }
            }
        } catch (failure: VaultFailure) {
            throw failure
        } catch (_: GeneralSecurityException) {
            throw VaultFailure(VaultFailureKind.UNAVAILABLE)
        } catch (_: IOException) {
            throw VaultFailure(VaultFailureKind.UNAVAILABLE)
        }
    }

    private fun mac(key: SecretKey, challenge: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(key)
        doFinal(challenge)
    }
    companion object { private val processLock = Any() }
}
