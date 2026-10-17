package dev.epay.observer.probe

import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest

object InstalledSource {
    enum class Status { MATCH, MISSING, MISMATCH, UNAVAILABLE }

    fun check(context: Context, postingUid: Int? = null): Status = try {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(AlipaySource.PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
        val certificates = info.signingInfo?.apkContentsSigners?.map {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) }
        }?.toSet().orEmpty()
        val installedUid = info.applicationInfo?.uid
        if (AlipaySource.accepts(info.packageName, AlipaySource.PACKAGE, true,
                installedUid != null && (postingUid == null || postingUid == installedUid),
                info.longVersionCode, certificates)) Status.MATCH else Status.MISMATCH
    } catch (_: PackageManager.NameNotFoundException) {
        Status.MISSING
    } catch (_: RuntimeException) {
        Status.UNAVAILABLE
    } catch (_: java.security.GeneralSecurityException) {
        Status.UNAVAILABLE
    }
}
