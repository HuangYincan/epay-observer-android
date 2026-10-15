package dev.epay.observer.capture

import android.app.Notification
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class ObserverListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        CaptureState.connection(true)
        try {
            activeNotifications?.forEach { capture(it, restored = true) }
        } catch (_: RuntimeException) {
            CaptureState.failure("active_snapshot_unavailable")
        }
    }

    override fun onListenerDisconnected() {
        CaptureState.connection(false)
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        CaptureState.connection(false)
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (CaptureState.connected) capture(sbn, restored = false)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (!CaptureState.connected) return
        // Only updates existing identities. Never reads extras or creates records.
        CaptureState.journal.removed(getSystemService(android.os.UserManager::class.java).getSerialNumberForUser(sbn.user), sbn.packageName, sbn.key)
        CaptureState.changed()
    }

    private fun capture(sbn: StatusBarNotification, restored: Boolean) {
        val source = try { SourcePolicy.resolve(this, sbn) } catch (_: android.content.pm.PackageManager.NameNotFoundException) { null }
            catch (_: RuntimeException) { null }
        if (source == null) { CaptureState.reject(); return }
        val fields = extractFields(sbn.notification)
        CaptureState.journal.record(ObservationInput(
            key = sbn.key, source = source, postTime = sbn.postTime,
            wallTime = System.currentTimeMillis(), elapsedTime = SystemClock.elapsedRealtime(),
            restored = restored, fields = fields,
        ))
        CaptureState.changed()
    }
}

internal fun extractFields(notification: Notification): NotificationFields {
    var unsupported = false
    fun bounded(value: Any?): String? {
        if (value == null) return null
        if (value !is CharSequence || value.length > 512) { unsupported = true; return null }
        return value.toString()
    }
    var title: String? = null
    var text: String? = null
    var bigText: String? = null
    var channel: String? = null
    var category: String? = null
    var group: String? = null
    try {
        // Bundle#get is used deliberately: getCharSequence can silently coerce a type mismatch to null.
        @Suppress("DEPRECATION")
        title = bounded(notification.extras.get(Notification.EXTRA_TITLE))
        @Suppress("DEPRECATION")
        text = bounded(notification.extras.get(Notification.EXTRA_TEXT))
        @Suppress("DEPRECATION")
        bigText = bounded(notification.extras.get(Notification.EXTRA_BIG_TEXT))
        channel = bounded(notification.channelId)
        category = bounded(notification.category)
        group = bounded(notification.group)
    } catch (_: RuntimeException) {
        unsupported = true
    }
    return NotificationFields(title, text, bigText, channel, category, notification.flags, group, unsupported)
}
