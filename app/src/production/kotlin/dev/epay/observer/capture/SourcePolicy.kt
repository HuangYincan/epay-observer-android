package dev.epay.observer.capture

import android.content.Context
import android.service.notification.StatusBarNotification

/** Empty until real source certificates, versions and templates have verified evidence. */
object SourcePolicy {
    @Suppress("UNUSED_PARAMETER")
    fun resolve(context: Context, sbn: StatusBarNotification): SourceIdentity? = null
}
