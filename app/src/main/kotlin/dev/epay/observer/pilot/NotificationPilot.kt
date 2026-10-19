package dev.epay.observer.pilot

import dev.epay.observer.capture.SourceIdentity
import dev.epay.observer.parsing.MinorAmount
import dev.epay.observer.parsing.Provider
import dev.epay.observer.storage.BindingSnapshot
import dev.epay.observer.storage.IdentityEncoding

/** Operator review facts, never inferred from a syntax match or a successful build. */
data class PilotReview(
    val evidenceReference: String,
    val templateVerified: Boolean, val chatBoundaryVerified: Boolean,
    val identityPolicyApproved: Boolean, val amountPolicyApproved: Boolean,
) {
    fun complete(): Boolean = Regex("[A-Za-z0-9_-]{1,64}").matches(evidenceReference) &&
        templateVerified && chatBoundaryVerified && identityPolicyApproved &&
        amountPolicyApproved
}

data class PilotConfiguration(
    val binding: BindingSnapshot, val amountMinor: Long,
    val serverArmedAt: Long, val serverExpiresAt: Long,
    val review: PilotReview,
)

/** Pins the installed D0 artifact. The pin does not authenticate a transaction. */
object PilotAlipaySource {
    const val PACKAGE = "com.eg.android.AlipayGphone"
    const val VERSION = 212310L
    const val CERTIFICATE = "389b49f7832f53e9017923220aa85e14dfaa4886ecd7428818bf339543cf498a"
    fun accepts(source: SourceIdentity): Boolean = source.packageName == PACKAGE &&
        source.opPackage == PACKAGE && source.user >= 0 && source.version == VERSION &&
        source.signers == setOf(CERTIFICATE)
}

/** Immutable local capability attached before extras are read. Never serialized. */
class PilotPermit internal constructor(
    internal val session: NotificationPilot,
    val configuration: PilotConfiguration, val source: SourceIdentity,
    val postTime: Long, val wallTime: Long, val elapsedTime: Long,
) {
    @Volatile internal var readStarted = false
    @Volatile internal var fieldsFingerprint: String? = null
    fun readFields(wall: Long, elapsed: Long, read: () -> dev.epay.observer.capture.NotificationFields):
        dev.epay.observer.capture.NotificationFields? = session.readFields(this,wall,elapsed,read)
    fun matches(input: dev.epay.observer.capture.ObservationInput): Boolean =
        input.pilotPermit === this && !input.restored && input.source == source &&
            input.postTime == postTime && input.wallTime == wallTime && input.elapsedTime == elapsedTime &&
            fieldsFingerprint != null && fieldsFingerprint == IdentityEncoding.fields(input.fields)
}

/** Process-local, one-shot gate; the persistent used marker belongs to the trusted controller. */
class NotificationPilot(private val configuration: PilotConfiguration,
                        private val startedWall: Long, private val startedElapsed: Long) {
    enum class Phase { RUNNING, LIMIT, EXPIRED, STOPPED, CLOCK_ERROR }
    private var phase = Phase.RUNNING
    private var reads = 0
    private var fieldReads = 0
    private var lastElapsed = startedElapsed
    init {
        require(configuration.review.complete()) { "review_incomplete" }
        require(configuration.binding.provider == Provider.ALIPAY &&
            configuration.binding.sourceClass == BindingSnapshot.SourceClass.NOTIFICATION) { "scope" }
        require(configuration.amountMinor in 1..MinorAmount.MAX) { "amount" }
        require(IdentityEncoding.validTime(configuration.serverArmedAt) &&
            IdentityEncoding.validTime(configuration.serverExpiresAt) &&
            configuration.serverExpiresAt > configuration.serverArmedAt &&
            configuration.serverExpiresAt - configuration.serverArmedAt <= 600_000 &&
            startedWall >= configuration.serverArmedAt && startedWall < configuration.serverExpiresAt &&
            IdentityEncoding.validTime(startedElapsed)) { "window" }
    }

    @Synchronized fun status(wall: Long, elapsed: Long): Phase {
        if (phase != Phase.RUNNING && phase != Phase.LIMIT) return phase
        if (!IdentityEncoding.validTime(wall) || !IdentityEncoding.validTime(elapsed) ||
            elapsed < lastElapsed || kotlin.math.abs((wall - startedWall) - (elapsed - startedElapsed)) > 2_000) {
            phase = Phase.CLOCK_ERROR
        } else if (elapsed - startedElapsed >= 120_000 || wall >= configuration.serverExpiresAt) {
            phase = Phase.EXPIRED
        }
        lastElapsed = elapsed
        return phase
    }

    /** Call only after package/user/UID/signature verification, and before extractFields. */
    @Synchronized fun admit(source: SourceIdentity, postTime: Long, wall: Long, elapsed: Long,
                            restored: Boolean): PilotPermit? {
        if (status(wall, elapsed) != Phase.RUNNING || restored || !PilotAlipaySource.accepts(source) ||
            !IdentityEncoding.validTime(postTime) || postTime < startedWall || postTime > wall || wall - postTime > 120_000) return null
        reads++
        if (reads == 2) phase = Phase.LIMIT
        return PilotPermit(this, configuration, source.copy(signers = source.signers.toSet()), postTime, wall, elapsed)
    }

    /** Recheck expiry/disconnect after source resolution, and serialize field reads with stop. */
    @Synchronized internal fun readFields(permit: PilotPermit, wall: Long, elapsed: Long,
        read: () -> dev.epay.observer.capture.NotificationFields): dev.epay.observer.capture.NotificationFields? {
        if (permit.session !== this || permit.readStarted ||
            status(wall,elapsed) !in setOf(Phase.RUNNING,Phase.LIMIT)) return null
        permit.readStarted = true
        fieldReads++
        return read().also { permit.fieldsFingerprint = IdentityEncoding.fields(it) }
    }
    @Synchronized fun stop() { if (phase == Phase.RUNNING || phase == Phase.LIMIT) phase = Phase.STOPPED }
    @Synchronized fun readCount(): Int = reads
    @Synchronized fun fieldReadCount(): Int = fieldReads
}

/** No HTTP/Intent/receiver entry point. Release and fixture compile a false capability. */
object PilotRuntime {
    @Volatile private var session: NotificationPilot? = null
    val hasSession: Boolean get() = session != null
    @Synchronized fun arm(configuration: PilotConfiguration, wall: Long, elapsed: Long) {
        check(PilotBuild.allowed) { "build_denied" }
        check(session == null) { "already_used" }
        session = NotificationPilot(configuration.copy(), wall, elapsed)
    }
    fun running(wall: Long, elapsed: Long): Boolean = PilotBuild.allowed &&
        session?.status(wall, elapsed) == NotificationPilot.Phase.RUNNING
    fun admit(source: SourceIdentity, postTime: Long, wall: Long, elapsed: Long, restored: Boolean): PilotPermit? =
        if (PilotBuild.allowed) session?.admit(source, postTime, wall, elapsed, restored) else null
    fun stop() { session?.stop() }
    fun readCount(): Int = session?.readCount() ?: 0
    fun fieldReadCount(): Int = session?.fieldReadCount() ?: 0
}
