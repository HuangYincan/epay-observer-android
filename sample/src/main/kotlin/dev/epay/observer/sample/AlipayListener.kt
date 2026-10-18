package dev.epay.observer.sample

import android.app.Notification
import android.os.Process
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class AlipayListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        SampleRuntime.gate(this).connection(true)
        // Do not enumerate notifications or resume a window on reconnect.
    }
    override fun onListenerDisconnected() {
        SampleRuntime.gate(this).connection(false)
        super.onListenerDisconnected()
    }
    override fun onDestroy() {
        SampleRuntime.gate(this).connection(false)
        super.onDestroy()
    }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val gate = SampleRuntime.gate(this)
        if (gate.snapshot().phase != SampleGate.Phase.RUNNING || sbn.packageName != AlipaySource.PACKAGE) return
        val matches = AlipaySource.permitsEnvelope(sbn.packageName, sbn.opPkg, sbn.user == Process.myUserHandle()) &&
            InstalledSource.check(this, sbn.uid) == InstalledSource.Status.MATCH
        gate.posted(matches,
            { sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE) },
            { sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT) })
    }
}
