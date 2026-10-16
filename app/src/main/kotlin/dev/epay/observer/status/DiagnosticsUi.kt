package dev.epay.observer.status

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import dev.epay.observer.R
import dev.epay.observer.capture.CaptureState
import dev.epay.observer.capture.ObserverListener
import dev.epay.observer.parsing.Provider
import dev.epay.observer.security.PairingPolicy
import dev.epay.observer.storage.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class DiagnosticReader(private val context: android.content.Context, private val db: ObserverDatabase) {
    fun snapshot(permission: Boolean, connected: Boolean): DiagnosticSnapshot {
        val stats = IdentityStore(db).stats()
        val pairing = PairingStore(db)
        @Suppress("DEPRECATION")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        return DiagnosticSnapshot(version, Build.VERSION.SDK_INT, PairingPolicy.permits(BindingSnapshot.SourceClass.SYNTHETIC),
            permission, connected, pairing.active(Provider.WXPAY) != null, pairing.active(Provider.ALIPAY) != null,
            stats.observations, stats.events, stats.conflicts, stats.gap, stats.capacityBlocked,
            db.outboxDao().counts().associate { it.state to it.count }, db.outboxDao().latestReceipt() != null)
    }
}

/** Local read-only summaries. Export is a separate, user-selected document action. */
class DiagnosticsUi(private val activity: Activity, savedState: Bundle? = null) {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "observer-diagnostics") }
    private val refreshing = AtomicBoolean(false)
    private val database = ObserverDatabase.open(activity)
    private var draft: String? = savedState?.getString(DRAFT_KEY)?.takeIf { it.length <= MAX_DRAFT_LENGTH }
    private val checking = AtomicBoolean(false)

    init {
        activity.findViewById<Button>(R.id.refresh_events).setOnClickListener { refresh() }
        activity.findViewById<Button>(R.id.preview_diagnostics).setOnClickListener { preview() }
        activity.findViewById<Button>(R.id.check_authorization).setOnClickListener { checkAuthorization() }
    }
    fun refresh() {
        if (activity.isDestroyed || !refreshing.compareAndSet(false, true)) return
        worker.execute {
            val text = try {
                val rows = database.observerDao().recentEvents()
                if (rows.isEmpty()) activity.getString(R.string.recent_events_empty) else rows.joinToString("\n\n") { row ->
                    val provider = when (row.provider) { "wxpay" -> "微信"; "alipay" -> "支付宝"; else -> "未知渠道" }
                    val id = if (dev.epay.observer.protocol.PairingProtocol.uuid.matches(row.eventId)) row.eventId.take(8) else "未知编号"
                    val amount = String.format(java.util.Locale.ROOT, "%d.%02d", row.amountMinor / 100, row.amountMinor % 100)
                    val time = if (IdentityEncoding.validTime(row.observedAt))
                        java.text.DateFormat.getDateTimeInstance().format(java.util.Date(row.observedAt)) else "未知时间"
                    "$provider · ¥$amount · $id\n$time\n${EventStatusText.state(row.localState, row.deliveryState)}" +
                        (EventStatusText.reason(row.reason)?.let { "\n$it" } ?: "")
                }
            } catch (_: Exception) { activity.getString(R.string.diagnostic_read_failed) }
            refreshing.set(false)
            activity.runOnUiThread { if (!activity.isDestroyed) activity.findViewById<TextView>(R.id.recent_events).text = text }
        }
    }
    private fun preview() {
        val permission = activity.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(activity, ObserverListener::class.java))
        val connected = CaptureState.connected
        worker.execute {
            val json = try { DiagnosticReport.encode(DiagnosticReader(activity, database).snapshot(permission, connected)) }
                catch (_: Exception) { null }
            activity.runOnUiThread {
                if (!activity.isDestroyed) {
                    if (json == null) message(R.string.diagnostic_read_failed)
                    else {
                        val text = TextView(activity).apply {
                            id = R.id.diagnostic_preview
                            setPadding(24, 16, 24, 16)
                            typeface = android.graphics.Typeface.MONOSPACE; this.text = json
                        }
                        AlertDialog.Builder(activity).setTitle(R.string.diagnostic_preview_title)
                            .setView(ScrollView(activity).apply { addView(text) })
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(R.string.diagnostic_export) { _, _ ->
                                draft = json
                                try {
                                    @Suppress("DEPRECATION")
                                    activity.startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT)
                                        .addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
                                        .putExtra(Intent.EXTRA_TITLE, "observer-diagnostics.json"), EXPORT_REQUEST)
                                } catch (_: Exception) { draft = null; message(R.string.diagnostic_export_failed) }
                            }.show()
                    }
                }
            }
        }
    }
    fun documentResult(resultCode: Int, uri: Uri?) {
        val json = draft; draft = null
        if (resultCode != Activity.RESULT_OK) return
        if (uri == null || json == null) { message(R.string.diagnostic_export_failed); return }
        if (uri.scheme != "content") { message(R.string.diagnostic_export_failed); return }
        worker.execute {
            val success = try {
                activity.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    ?: error("document_unavailable")
                true
            } catch (_: Exception) { false }
            activity.runOnUiThread {
                if (!activity.isDestroyed) message(if (success) R.string.diagnostic_exported else R.string.diagnostic_export_failed)
            }
        }
    }
    private fun message(id: Int) { activity.findViewById<TextView>(R.id.diagnostic_status).setText(id) }
    private fun checkAuthorization() {
        if (dev.epay.observer.security.PairingRuntime.busy || !checking.compareAndSet(false, true)) return
        activity.findViewById<TextView>(R.id.authorization_status).setText(R.string.authorization_checking)
        worker.execute {
            val text = try {
                val check = dev.epay.observer.protocol.HealthCheck(activity, database)
                Provider.entries.joinToString("\n") { provider ->
                    (if (provider == Provider.WXPAY) "微信：" else "支付宝：") + when (check.check(provider)) {
                        dev.epay.observer.protocol.HealthState.BUSY -> "配对正在进行，请稍后检查"
                        dev.epay.observer.protocol.HealthState.AUTHORIZED -> "检查时网关授权有效"
                        dev.epay.observer.protocol.HealthState.UNBOUND -> "未绑定"
                        dev.epay.observer.protocol.HealthState.LOCAL_KEY -> "本地凭据不可用，请修复配对"
                        dev.epay.observer.protocol.HealthState.CLOCK -> "请检查设备时间或凭据有效期"
                        dev.epay.observer.protocol.HealthState.CHANGED -> "授权状态已变化，请重新检查"
                        dev.epay.observer.protocol.HealthState.DENIED -> "网关拒绝授权，请核对配对与时间"
                        dev.epay.observer.protocol.HealthState.RATE_LIMITED -> "检查过于频繁，请稍后重试"
                        dev.epay.observer.protocol.HealthState.UNAVAILABLE -> "检查未完成，请核对网络、证书与网关"
                    }
                }
            } catch (_: Exception) { activity.getString(R.string.authorization_failed) }
            checking.set(false)
            activity.runOnUiThread { if (!activity.isDestroyed) activity.findViewById<TextView>(R.id.authorization_status).text = text }
        }
    }
    fun saveState(state: Bundle) {
        draft?.takeIf { it.length <= MAX_DRAFT_LENGTH }?.let { state.putString(DRAFT_KEY, it) }
    }
    fun close() { worker.execute { database.close() }; worker.shutdown(); draft = null }
    companion object {
        const val EXPORT_REQUEST = 2401
        private const val DRAFT_KEY = "diagnostic_export_draft"
        private const val MAX_DRAFT_LENGTH = 8192
    }
}
