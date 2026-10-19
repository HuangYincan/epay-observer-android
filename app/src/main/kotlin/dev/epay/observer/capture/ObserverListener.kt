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
        SourcePolicy.disconnected()
        CaptureState.connection(false)
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        SourcePolicy.disconnected()
        CaptureState.connection(false)
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (CaptureState.connected) capture(sbn, restored = false)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (!CaptureState.connected || !SourcePolicy.isCandidate(sbn)) return
        // Only updates existing identities. Never reads extras or creates records.
        dev.epay.observer.storage.DurableCapture.removed(
            getSystemService(android.os.UserManager::class.java).getSerialNumberForUser(sbn.user), sbn.packageName, sbn.key)
        CaptureState.journal.removed(getSystemService(android.os.UserManager::class.java).getSerialNumberForUser(sbn.user), sbn.packageName, sbn.key)
        CaptureState.changed()
    }

    private fun capture(sbn: StatusBarNotification, restored: Boolean) {
        // Cleanup uses this same looper as a barrier. Fail before extras on an unexpected executor.
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            CaptureState.failure("callback_thread"); return
        }
        val source = try { SourcePolicy.resolve(this, sbn) } catch (_: android.content.pm.PackageManager.NameNotFoundException) { null }
            catch (_: RuntimeException) { null }
        if (source == null) { CaptureState.reject(); return }
        val wall = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val permission = SourcePolicy.authorize(source, sbn.postTime, wall, elapsed, restored) ?: return
        val fields = if (permission.pilotPermit == null) extractFields(sbn.notification)
            else permission.pilotPermit.readFields(System.currentTimeMillis(),SystemClock.elapsedRealtime()) {
                extractFields(sbn.notification)
            } ?: return
        val input = ObservationInput(
            key = sbn.key, source = source, postTime = sbn.postTime,
            wallTime = wall, elapsedTime = elapsed,
            restored = restored, fields = fields, pilotPermit = permission.pilotPermit,
        )
        dev.epay.observer.storage.DurableCapture.accept(input)
        CaptureState.journal.record(input)
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
    var template: String? = null
    var additional = false
    try {
        // Bundle#get is used deliberately: getCharSequence can silently coerce a type mismatch to null.
        @Suppress("DEPRECATION")
        title = bounded(notification.extras.get(Notification.EXTRA_TITLE))
        @Suppress("DEPRECATION")
        text = bounded(notification.extras.get(Notification.EXTRA_TEXT))
        @Suppress("DEPRECATION")
        bigText = bounded(notification.extras.get(Notification.EXTRA_BIG_TEXT))
        @Suppress("DEPRECATION")
        template = bounded(notification.extras.get(Notification.EXTRA_TEMPLATE))
        @Suppress("DEPRECATION")
        additional = listOf(Notification.EXTRA_SUB_TEXT, Notification.EXTRA_SUMMARY_TEXT,
            Notification.EXTRA_TEXT_LINES, Notification.EXTRA_MESSAGES, Notification.EXTRA_HISTORIC_MESSAGES,
            Notification.EXTRA_TITLE_BIG).any { notification.extras.get(it) != null }
        channel = bounded(notification.channelId)
        category = bounded(notification.category)
        group = bounded(notification.group)
    } catch (_: RuntimeException) {
        unsupported = true
    }
    return NotificationFields(title, text, bigText, channel, category, notification.flags, group, unsupported, template, additional)
}
