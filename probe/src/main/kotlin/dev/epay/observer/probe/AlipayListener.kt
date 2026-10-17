package dev.epay.observer.probe

import android.os.Process
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

object ProbeRuntime { val window = ProbeWindow { SystemClock.elapsedRealtime() } }

class AlipayListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        ProbeRuntime.window.connection(true)
        // Do not enumerate existing notifications or resume an earlier window.
    }

    override fun onListenerDisconnected() {
        ProbeRuntime.window.connection(false)
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        ProbeRuntime.window.connection(false)
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (ProbeRuntime.window.snapshot().phase != ProbeWindow.Phase.RUNNING) return
        if (sbn.packageName != AlipaySource.PACKAGE) return
        // Never access sbn.notification, extras, key, id, tag, time or account data.
        val matches = AlipaySource.permitsEnvelope(sbn.packageName, sbn.opPkg, sbn.user == Process.myUserHandle()) &&
            InstalledSource.check(this, sbn.uid) == InstalledSource.Status.MATCH
        ProbeRuntime.window.posted(matches)
    }
}
