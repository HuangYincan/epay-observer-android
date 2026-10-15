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
        findViewById<Button>(R.id.reconnect).isEnabled = granted && !CaptureState.connected
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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
}
