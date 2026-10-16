package dev.epay.observer.storage

import dev.epay.observer.parsing.Provider
import dev.epay.observer.protocol.PairingEnrollment
import dev.epay.observer.protocol.PairingProtocol
import java.io.*
import java.util.Base64
import java.util.concurrent.Callable

data class ActiveCredential(val enrollmentId: String, val binding: BindingSnapshot, val keyId: String,
    val notBefore: Long, val expiresAt: Long, val captureEpoch: String = "", val uploadEpoch: String = "")

/** Stores non-secret authority metadata only. No device key, token or resume secret. */
class PairingStore(private val db: ObserverDatabase) {
    fun activate(enrollment: PairingEnrollment) = db.runInTransaction {
        val value = ActiveCredential(enrollment.enrollmentId, enrollment.binding, enrollment.keyId, enrollment.notBefore, enrollment.expiresAt)
        db.observerDao().putMetadata(MetadataRow(name(value.binding.provider), encode(value)))
        db.observerDao().putMetadata(MetadataRow(epochName(value.binding.provider), java.util.UUID.randomUUID().toString()))
        renewUploadEpoch(value.binding.provider)
        db.outboxDao().releaseScopeLeases(value.binding.scope)
        OutboxStore(db).activate(value.binding)
        db.outboxDao().resumePairing(value.binding.scope)
        db.observerDao().putMetadata(MetadataRow(uploadName(value.binding.provider), ""))
        db.outboxDao().resumeAuthorizedScope(value.binding.scope)
    }
    fun active(provider: Provider): ActiveCredential? = db.runInTransaction(Callable {
        val value = db.observerDao().metadata(name(provider))?.let(::decode) ?: return@Callable null
        val epoch = db.observerDao().metadata(epochName(provider)) ?: return@Callable null
        value.copy(captureEpoch = epoch, uploadEpoch = db.observerDao().metadata(uploadEpochName(provider)) ?: "").takeIf {
            PairingProtocol.uuid.matches(epoch) && db.observerDao().metadata(it.binding.metadataKey) == it.binding.scope
        }
    })
    fun revoke(provider: Provider) { OutboxStore(db).revoke(provider) }
    fun uploadHold(provider: Provider): String? = db.observerDao().metadata(uploadName(provider))?.takeIf { it.isNotEmpty() }
    fun uploadAllowed(value: ActiveCredential): Boolean = db.runInTransaction(Callable {
        active(value.binding.provider) == value &&
            db.observerDao().metadata(uploadName(value.binding.provider)).isNullOrEmpty()
    })
    /** Authority validation and lease acquisition share the rotation/repair transaction boundary. */
    fun claimUpload(value: ActiveCredential, now: Long): DeliveryLease? = db.runInTransaction(Callable {
        if (!uploadAllowed(value)) return@Callable null
        OutboxStore(db).claim(value.binding, now)
    })
    fun pauseUploads(value: ActiveCredential, reason: HoldReason, lease: DeliveryLease? = null): Boolean = db.runInTransaction(Callable {
        require(reason != HoldReason.CONFLICT)
        if (!uploadAllowed(value)) return@Callable false
        if (lease != null && !OutboxStore(db).hold(lease, reason)) return@Callable false
        db.observerDao().putMetadata(MetadataRow(uploadName(value.binding.provider), reason.name))
        db.outboxDao().holdScope(value.binding.scope, reason.name)
        true
    })
    /** Explicit retry after repair. Server authorization is still checked on the next request. */
    fun resumeUploads(provider: Provider) = db.runInTransaction {
        active(provider)?.let {
            renewUploadEpoch(provider)
            db.outboxDao().releaseScopeLeases(it.binding.scope)
            db.observerDao().putMetadata(MetadataRow(uploadName(provider), ""))
            db.outboxDao().resumeAuthorizedScope(it.binding.scope)
        }
    }
    fun pause(provider: Provider) = db.runInTransaction {
        db.observerDao().putMetadata(MetadataRow("binding:${provider.name.lowercase(java.util.Locale.ROOT)}", "pairing"))
        db.observerDao().putMetadata(MetadataRow(epochName(provider), "paused"))
        db.outboxDao().pausePairing(provider.name.lowercase(java.util.Locale.ROOT))
    }
    private fun name(provider: Provider) = "pairing:${provider.name}"
    private fun uploadName(provider: Provider) = "upload-hold:${provider.name}"
    private fun uploadEpochName(provider: Provider) = "upload-epoch:${provider.name}"
    private fun renewUploadEpoch(provider: Provider) {
        db.observerDao().putMetadata(MetadataRow(uploadEpochName(provider), java.util.UUID.randomUUID().toString()))
    }
    companion object { internal fun epochName(provider: Provider) = "capture-epoch:${provider.name}" }
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
