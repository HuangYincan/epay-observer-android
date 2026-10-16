package dev.epay.observer

import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.widget.Button
import android.widget.TextView
import dev.epay.observer.capture.CaptureState
import dev.epay.observer.capture.ObserverListener
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets

class MainActivity : Activity() {
    private lateinit var parserPreview: dev.epay.observer.parsing.ParserPreview
    private lateinit var pairingUi: dev.epay.observer.security.PairingUi
    private lateinit var diagnosticsUi: dev.epay.observer.status.DiagnosticsUi
    private val component get() = ComponentName(this, ObserverListener::class.java)
    private val stateChanged: () -> Unit = { runOnUiThread { render() } }

    override fun onResume() {
        super.onResume()
        CaptureState.subscribe(stateChanged)
        render()
    }
    override fun onPause() {
        CaptureState.unsubscribe(stateChanged)
        super.onPause()
    }
    private fun render() {
        val granted = getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component)
        findViewById<TextView>(R.id.permission_status).setText(if (granted) R.string.permission_granted else R.string.permission_missing)
        findViewById<TextView>(R.id.listener_status).setText(if (CaptureState.connected) R.string.listener_connected else R.string.listener_disconnected)
        val observations = CaptureState.journal.snapshots()
        findViewById<TextView>(R.id.observation_status).text = getString(R.string.observation_count, observations.size, CaptureState.rejected)
        findViewById<TextView>(R.id.capture_warning).setText(when {
            CaptureState.journal.overflow > 0 -> R.string.capture_full
            CaptureState.error != null -> R.string.capture_error
            else -> R.string.capture_memory_only
        })
        val persisted = dev.epay.observer.storage.DurableCapture.stats
        val storageError = dev.epay.observer.storage.DurableCapture.error
        findViewById<TextView>(R.id.persistence_status).text = when {
            storageError == "database" -> getString(R.string.persistence_database_error)
            storageError == "capacity" || persisted.capacityBlocked || persisted.observations >= 10_000 -> getString(R.string.persistence_capacity)
            storageError == "ingress_gap" || persisted.gap -> getString(R.string.persistence_gap)
            else -> getString(R.string.persistence_counts, persisted.observations, persisted.events, persisted.conflicts)
        }
        findViewById<TextView>(R.id.events_status).text = if (persisted.events == 0) getString(R.string.events_empty)
            else getString(R.string.local_events, persisted.events)
        parserPreview.render()
        pairingUi.render()
        findViewById<TextView>(R.id.upload_status).text = dev.epay.observer.protocol.UploadRuntime.status
        diagnosticsUi.refresh()
        findViewById<Button>(R.id.reconnect).isEnabled = granted && !CaptureState.connected
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        parserPreview = dev.epay.observer.parsing.ParserPreview(this)
        pairingUi = dev.epay.observer.security.PairingUi(this)
        diagnosticsUi = dev.epay.observer.status.DiagnosticsUi(this, savedInstanceState)
        findViewById<Button>(R.id.upload_now).setOnClickListener { dev.epay.observer.protocol.UploadRuntime.kick() }
        findViewById<Button>(R.id.retry_upload).setOnClickListener { dev.epay.observer.protocol.UploadRuntime.kick(retryAfterRepair = true) }

        findViewById<Button>(R.id.permission_settings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.reconnect).setOnClickListener {
            NotificationListenerService.requestRebind(component)
        }
        val root = findViewById<android.view.View>(R.id.page)
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom,
                )
            }
            insets
        }
        root.requestApplyInsets()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        diagnosticsUi.saveState(outState)
        super.onSaveInstanceState(outState)
    }
    @Deprecated("Platform activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == dev.epay.observer.status.DiagnosticsUi.EXPORT_REQUEST) diagnosticsUi.documentResult(resultCode, data?.data)
    }
    override fun onDestroy() {
        diagnosticsUi.close()
        super.onDestroy()
    }
}
