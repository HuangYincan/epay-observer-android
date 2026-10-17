package dev.epay.observer.probe

import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class ProbeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val component get() = ComponentName(this, AlipayListener::class.java)
    private lateinit var status: TextView
    private lateinit var start: Button
    private val refresh = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        fun text(res: Int, size: Float) = TextView(this).apply { setText(res); textSize = size; layout.addView(this) }
        fun button(res: Int, action: () -> Unit) = Button(this).apply {
            setText(res); setOnClickListener { action(); render() }; layout.addView(this)
        }
        text(R.string.title, 26f)
        text(R.string.boundary, 16f)
        status = text(R.string.idle, 18f).apply { id = R.id.probe_status }
        button(R.string.permission) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        button(R.string.reconnect) {
            if (hasAccess()) NotificationListenerService.requestRebind(component)
        }
        start = button(R.string.start) {
            if (hasAccess()) ProbeRuntime.window.start(InstalledSource.check(this) == InstalledSource.Status.MATCH)
        }.apply { id = R.id.probe_start }
        button(R.string.stop) { ProbeRuntime.window.stop() }.id = R.id.probe_stop
        text(R.string.instructions, 16f)
        val scroll = ScrollView(this).apply { addView(layout) }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(scroll)
        scroll.requestApplyInsets()
    }

    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }

    private fun hasAccess() = getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component)

    private fun render() {
        val access = hasAccess()
        if (!access) ProbeRuntime.window.connection(false)
        val source = InstalledSource.check(this)
        if (source != InstalledSource.Status.MATCH && ProbeRuntime.window.snapshot().phase == ProbeWindow.Phase.RUNNING) {
            ProbeRuntime.window.posted(false)
        }
        val state = ProbeRuntime.window.snapshot()
        val phaseText = when (state.phase) {
            ProbeWindow.Phase.IDLE -> R.string.idle
            ProbeWindow.Phase.RUNNING -> R.string.running
            ProbeWindow.Phase.STOPPED -> R.string.stopped
            ProbeWindow.Phase.EXPIRED -> R.string.expired
            ProbeWindow.Phase.DISCONNECTED -> R.string.disconnected
            ProbeWindow.Phase.SOURCE_CHANGED -> R.string.source_changed
            ProbeWindow.Phase.CAPACITY -> R.string.capacity
            ProbeWindow.Phase.CLOCK_ERROR -> R.string.clock_error
        }
        val sourceText = when (source) {
            InstalledSource.Status.MATCH -> R.string.source_match
            InstalledSource.Status.MISSING -> R.string.source_missing
            InstalledSource.Status.MISMATCH -> R.string.source_mismatch
            InstalledSource.Status.UNAVAILABLE -> R.string.source_unavailable
        }
        status.text = getString(R.string.status, getString(if (access) R.string.allowed else R.string.denied),
            getString(if (state.connected) R.string.connected else R.string.not_connected),
            getString(sourceText), getString(phaseText), state.callbacks, state.remainingSeconds)
        start.isEnabled = access && state.connected && source == InstalledSource.Status.MATCH && state.phase != ProbeWindow.Phase.RUNNING
    }
}
