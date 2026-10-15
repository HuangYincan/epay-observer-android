package dev.epay.observer.fixture

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Synthetic notifications only: no orders, payment credentials or network permission. */
class FixtureActivity : Activity() {
    private lateinit var status: TextView
    private val manager get() = getSystemService(NotificationManager::class.java)
    private val scenarios = listOf("wxpay", "alipay", "identical-pair", "update", "repost",
        "summary", "child", "refund", "expense", "chat", "malformed", "oversized", "clear")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager.createNotificationChannel(NotificationChannel("synthetic-v1", "合成通知（非真实收款）", NotificationManager.IMPORTANCE_DEFAULT))
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 70, 32, 70) }
        status = TextView(this).apply { text = "独立通知夹具 · 无真实资金\n先授权发送通知，再选择场景。"; textSize = 18f }
        layout.addView(status)
        layout.addView(Button(this).apply {
            text = "允许发送通知"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        })
        scenarios.forEach { scenario -> layout.addView(Button(this).apply { text = scenario; setOnClickListener { post(scenario) } }) }
        setContentView(ScrollView(this).apply { addView(layout) })
        intent.getStringExtra("scenario")?.let { post(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("scenario")?.let { post(it) }
    }

    private fun post(scenario: String) {
        if (scenario !in scenarios) return
        if (scenario == "clear") { manager.cancelAll(); status.text = "已撤下夹具通知"; return }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            status.text = "尚未授予发送通知权限"; return
        }
        fun send(id: Int, title: String = "微信形态 · 合成收款", text: String = "收款 10.00 元", group: Boolean = false, summary: Boolean = false, malformed: Boolean = false) {
            val builder = Notification.Builder(this, "synthetic-v1")
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text)).setOnlyAlertOnce(true)
                .setCategory(if (scenario == "chat") Notification.CATEGORY_MESSAGE else Notification.CATEGORY_STATUS)
            if (group) builder.setGroup("synthetic-group").setGroupSummary(summary)
            val notification = builder.build()
            if (malformed) notification.extras.putInt(Notification.EXTRA_TEXT, 42)
            manager.notify(id, notification)
        }
        when (scenario) {
            "wxpay", "repost" -> send(101)
            "alipay" -> send(102, "支付宝形态 · 合成到账", "到账 10.00 元")
            "identical-pair" -> { send(201); send(202) }
            "update" -> send(101, text = "收款 20.00 元")
            "summary" -> send(301, text = "收到 2 条消息", group = true, summary = true)
            "child" -> send(302, group = true)
            "refund" -> send(401, "合成退款", "退款 10.00 元")
            "expense" -> send(402, "合成支出", "付款 10.00 元")
            "chat" -> send(403, "合成聊天", "朋友说：收款 10.00 元")
            "malformed" -> send(501, malformed = true)
            "oversized" -> send(502, text = "合".repeat(600))
        }
        status.text = getString(R.string.posted, scenario)
    }
}
