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
    private val scenarios = listOf("wxpay", "alipay", "fresh-wxpay", "fresh-alipay", "fresh-wxpay-20", "fresh-alipay-20", "fresh-wxpay-40", "identical-pair", "update", "repost",
        "summary", "child", "refund", "expense", "chat", "malformed", "oversized", "clear", "parse-wx", "parse-ali", "parse-wx-update",
        "wx-conflict", "ali-conflict", "wx-currency", "ali-currency", "wx-refund", "ali-refund",
        "wx-chat", "ali-chat", "wx-promotion", "ali-promotion", "wx-inbox", "ali-inbox",
        "wx-precision", "ali-precision", "wx-subtext", "ali-subtext")

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
        fun send(id: Int, title: String = "微信形态 · 合成收款", text: String = "收款 10.00 元", group: Boolean = false, summary: Boolean = false, malformed: Boolean = false, bigText: String = text, inbox: Boolean = false, subText: String? = null) {
            val builder = Notification.Builder(this, "synthetic-v1")
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(text)
                .setStyle(if (inbox) Notification.InboxStyle().addLine("收款 10.00 元").addLine("收款 20.00 元") else Notification.BigTextStyle().bigText(bigText)).setOnlyAlertOnce(true)
                .setCategory(if (scenario == "chat" || scenario.endsWith("-chat")) Notification.CATEGORY_MESSAGE else Notification.CATEGORY_STATUS)
            if (subText != null) builder.setSubText(subText)
            if (group) builder.setGroup("synthetic-group").setGroupSummary(summary)
            val notification = builder.build()
            if (malformed) notification.extras.putInt(Notification.EXTRA_TEXT, 42)
            if (scenario.startsWith("fresh-")) manager.notify(java.util.UUID.randomUUID().toString(), id, notification)
            else manager.notify(id, notification)
        }
        val wxTitle = "微信形态 · 合成收款"
        val aliTitle = "支付宝形态 · 合成到账"
        when (scenario) {
            "parse-wx" -> send(610, wxTitle)
            "parse-ali" -> send(611, aliTitle, "到账 10.00 元")
            "parse-wx-update" -> send(610, wxTitle, "收款 20.00 元")
            "wx-conflict" -> send(620, wxTitle, bigText = "收款 20.00 元")
            "ali-conflict" -> send(621, aliTitle, "到账 10.00 元", bigText = "到账 20.00 元")
            "wx-currency" -> send(622, wxTitle, "收款 10.00 USD")
            "ali-currency" -> send(623, aliTitle, "到账 10.00 USD")
            "wx-refund" -> send(624, wxTitle, "退款 10.00 元")
            "ali-refund" -> send(625, aliTitle, "退款 10.00 元")
            "wx-chat" -> send(626, wxTitle)
            "ali-chat" -> send(627, aliTitle, "到账 10.00 元")
            "wx-promotion" -> send(628, "合成推广", "收款立减 10.00 元")
            "ali-promotion" -> send(629, "合成推广", "到账奖励 10.00 元")
            "wx-inbox" -> send(630, wxTitle, inbox = true)
            "ali-inbox" -> send(631, aliTitle, "到账 10.00 元", inbox = true)
            "wx-precision" -> send(632, wxTitle, "收款 10.001 元")
            "ali-precision" -> send(633, aliTitle, "到账 10.001 元")
            "wx-subtext" -> send(634, wxTitle, subText = "另一笔 20.00 元")
            "ali-subtext" -> send(635, aliTitle, "到账 10.00 元", subText = "另一笔 20.00 元")
            "wxpay", "repost" -> send(101)
            "alipay" -> send(102, "支付宝形态 · 合成到账", "到账 10.00 元")
            // New platform notification keys model separate receipts. No order
            // identifier or hidden payment metadata is included in the payload.
            "fresh-wxpay" -> send(101)
            "fresh-alipay" -> send(102, "支付宝形态 · 合成到账", "到账 10.00 元")
            "fresh-wxpay-20" -> send(103, wxTitle, "收款 20.00 元")
            "fresh-alipay-20" -> send(104, aliTitle, "到账 20.00 元")
            "fresh-wxpay-40" -> send(105, wxTitle, "收款 40.00 元")
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
