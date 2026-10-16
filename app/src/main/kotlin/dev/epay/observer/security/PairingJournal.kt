package dev.epay.observer.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dev.epay.observer.protocol.EnrollmentRequest
import dev.epay.observer.protocol.PairingProtocol
import dev.epay.observer.protocol.PairingScope
import dev.epay.observer.protocol.bounded
import java.io.*
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class PendingPairing(val origin: String, val token: String, val scopeBytes: ByteArray, val request: EnrollmentRequest) {
    val scope: PairingScope get() = PairingProtocol.scope(scopeBytes, origin)
    init { require(PairingProtocol.validSecret(token)); scope }
    override fun toString() = "PendingPairing(redacted)"
}

/** Caller serializes the entire enrollment workflow, including network I/O. Only
 * encrypted bytes reach the atomic journal; the AES key stays in AndroidKeyStore. */
class PairingJournal(context: Context, private val namespace: String = "pairing") {
    private val file: AtomicFile
    private val alias: String
    private val aad: ByteArray
    init {
        require(Regex("[a-z-]{1,40}").matches(namespace))
        file = AtomicFile(File(context.noBackupFilesDir, "observer-$namespace.pending"))
        alias = "observer.pending.v1.$namespace"
        aad = (context.packageName + ":" + alias).toByteArray(Charsets.UTF_8)
    }
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create && !store.containsAlias(alias)) { "pairing_key_missing" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).setUserAuthenticationRequired(false).build())
            generateKey()
        }
    }
    fun save(pending: PendingPairing) {
        check(!file.baseFile.exists()) { "pairing_pending_exists" }
        val plain = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(1); out.writeUTF(pending.origin); out.writeUTF(pending.token)
                out.writeInt(pending.scopeBytes.size); out.write(pending.scopeBytes)
                out.writeUTF(pending.request.enrollmentId); out.writeUTF(pending.request.resumeSecret)
            }; buffer.toByteArray()
        }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(true)); cipher.updateAAD(aad)
            val encrypted = cipher.doFinal(plain)
            check(cipher.iv.size == 12)
            val output = file.startWrite()
            try { output.write(byteArrayOf(1)); output.write(cipher.iv); output.write(encrypted); file.finishWrite(output) }
            catch (failure: Exception) { file.failWrite(output); throw failure }
        } finally { plain.fill(0) }
    }
    fun read(): PendingPairing? {
        val bytes = try { file.openRead().use { input ->
            input.bounded(8192)
        } } catch (_: FileNotFoundException) { return null }
        check(bytes.size >= 29 && bytes[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        cipher.updateAAD(aad)
        val plain = cipher.doFinal(bytes, 13, bytes.size - 13)
        return try {
            DataInputStream(ByteArrayInputStream(plain)).use { input ->
                check(input.readInt() == 1)
                val origin = input.readUTF(); val token = input.readUTF(); val size = input.readInt()
                check(size in 1..4096)
                val scope = ByteArray(size); input.readFully(scope)
                val request = EnrollmentRequest(input.readUTF(), input.readUTF()); check(input.read() == -1)
                PendingPairing(origin, token, scope, request)
            }
        } finally { plain.fill(0) }
    }
    fun clear() { file.delete(); check(!file.baseFile.exists()) }
}
