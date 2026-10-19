package dev.epay.observer.capture

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import dev.epay.observer.pilot.PilotAlipaySource
import dev.epay.observer.pilot.PilotRuntime
import java.security.MessageDigest

/** Extras remain inaccessible unless a separately reviewed debug session is running. */
object SourcePolicy {
    fun isCandidate(sbn: StatusBarNotification): Boolean =
        PilotRuntime.running(System.currentTimeMillis(), SystemClock.elapsedRealtime()) &&
            sbn.packageName == PilotAlipaySource.PACKAGE && sbn.opPkg == sbn.packageName &&
            sbn.user == Process.myUserHandle()

    fun resolve(context: Context, sbn: StatusBarNotification): SourceIdentity? {
        if (!isCandidate(sbn)) return null
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(PilotAlipaySource.PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
        if (info.applicationInfo?.uid != sbn.uid) return null
        val signers = info.signingInfo?.apkContentsSigners?.map {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) }
        }?.toSet().orEmpty()
        val user = context.getSystemService(android.os.UserManager::class.java).getSerialNumberForUser(sbn.user)
        return SourceIdentity(info.packageName, user, info.longVersionCode, signers, sbn.opPkg)
            .takeIf(PilotAlipaySource::accepts)
    }

    fun authorize(source: SourceIdentity, postTime: Long, wall: Long, elapsed: Long, restored: Boolean): CapturePermission? =
        PilotRuntime.admit(source, postTime, wall, elapsed, restored)?.let(::CapturePermission)
    fun disconnected() { PilotRuntime.stop() }
}
