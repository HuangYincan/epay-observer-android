package dev.epay.observer.capture

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.service.notification.StatusBarNotification
import java.security.MessageDigest

/** Only compiled into the isolated client. Both APKs must be signed by the same local build key. */
object SourcePolicy {
    private const val PACKAGE = "dev.epay.observer.fixture"
    fun isCandidate(sbn: StatusBarNotification): Boolean =
        sbn.packageName == PACKAGE && sbn.opPkg == PACKAGE && sbn.user == Process.myUserHandle()
    fun resolve(context: Context, sbn: StatusBarNotification): SourceIdentity? {
        if (!isCandidate(sbn)) return null
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val sender = try { pm.getPackageInfo(PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES) }
            catch (_: PackageManager.NameNotFoundException) { return null }
        @Suppress("DEPRECATION")
        val self = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        fun digests(info: android.content.pm.PackageInfo): Set<String> =
            info.signingInfo?.apkContentsSigners?.map { signature ->
                MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                    .joinToString("") { "%02x".format(it) }
            }?.toSet().orEmpty()
        val user = context.getSystemService(android.os.UserManager::class.java).getSerialNumberForUser(sbn.user)
        if (user < 0) return null
        val signers = digests(sender)
        if (!SourceBoundary.allows(sbn.packageName, sbn.opPkg, user,
                user, PACKAGE, signers, digests(self))) return null
        return SourceIdentity(PACKAGE, user, sender.longVersionCode, signers, sbn.opPkg)
    }
}
