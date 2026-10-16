package dev.epay.observer.storage

import dev.epay.observer.parsing.Provider
import dev.epay.observer.protocol.PairingEnrollment
import dev.epay.observer.protocol.PairingProtocol
import java.io.*
import java.util.Base64
import java.util.concurrent.Callable

data class ActiveCredential(val enrollmentId: String, val binding: BindingSnapshot, val keyId: String,
    val notBefore: Long, val expiresAt: Long)

/** Stores non-secret authority metadata only. No device key, token or resume secret. */
class PairingStore(private val db: ObserverDatabase) {
    fun activate(enrollment: PairingEnrollment) = db.runInTransaction {
        val value = ActiveCredential(enrollment.enrollmentId, enrollment.binding, enrollment.keyId, enrollment.notBefore, enrollment.expiresAt)
        db.observerDao().putMetadata(MetadataRow(name(value.binding.provider), encode(value)))
        OutboxStore(db).activate(value.binding)
        db.outboxDao().resumePairing(value.binding.scope)
    }
    fun active(provider: Provider): ActiveCredential? = db.runInTransaction(Callable {
        val value = db.observerDao().metadata(name(provider))?.let(::decode) ?: return@Callable null
        value.takeIf { db.observerDao().metadata(it.binding.metadataKey) == it.binding.scope }
    })
    fun revoke(provider: Provider) { OutboxStore(db).revoke(provider) }
    fun pause(provider: Provider) = db.runInTransaction {
        db.observerDao().putMetadata(MetadataRow("binding:${provider.name.lowercase(java.util.Locale.ROOT)}", "pairing"))
        db.outboxDao().pausePairing(provider.name.lowercase(java.util.Locale.ROOT))
    }
    private fun name(provider: Provider) = "pairing:${provider.name}"
    private fun encode(value: ActiveCredential): String = ByteArrayOutputStream().use { buffer ->
        DataOutputStream(buffer).use { out ->
            out.writeInt(1); out.writeUTF(value.enrollmentId)
            with(value.binding) {
                out.writeUTF(origin); out.writeUTF(environment); out.writeUTF(deviceId); out.writeUTF(receiverId)
                out.writeInt(version); out.writeUTF(provider.name); out.writeUTF(sourceClass.name)
            }
            out.writeUTF(value.keyId); out.writeLong(value.notBefore); out.writeLong(value.expiresAt)
        }
        Base64.getEncoder().encodeToString(buffer.toByteArray())
    }
    private fun decode(text: String): ActiveCredential {
        require(text.length <= 4096)
        return DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(text))).use { input ->
            require(input.readInt() == 1)
            val enrollment = input.readUTF()
            val binding = BindingSnapshot(input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF(),
                input.readInt(), Provider.valueOf(input.readUTF()), BindingSnapshot.SourceClass.valueOf(input.readUTF()))
            val key = input.readUTF(); val start = input.readLong(); val end = input.readLong()
            require(PairingProtocol.uuid.matches(enrollment) && PairingProtocol.id.matches(key) &&
                IdentityEncoding.validTime(start) && IdentityEncoding.validTime(end) && end > start && input.read() == -1)
            ActiveCredential(enrollment, binding, key, start, end)
        }
    }
}
