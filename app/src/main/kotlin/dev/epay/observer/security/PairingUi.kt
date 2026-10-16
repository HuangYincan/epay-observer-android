package dev.epay.observer.security

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.WindowManager
import android.widget.*
import dev.epay.observer.R

class PairingUi(private val activity: Activity) {
    init {
        activity.findViewById<Button>(R.id.pair_device).setOnClickListener { input() }
        activity.findViewById<Button>(R.id.recover_pairing).setOnClickListener { PairingRuntime.recover() }
        activity.findViewById<Button>(R.id.cancel_pairing).setOnClickListener {
            AlertDialog.Builder(activity).setTitle("取消本机待完成配对？")
                .setMessage("本机将停止该渠道的采集绑定。请同时在网关撤销已兑换的绑定，再用新授权重新配对。")
                .setNegativeButton("返回", null).setPositiveButton("取消待完成配对") { _, _ -> PairingRuntime.forget() }.show()
        }
    }
    fun render() {
        activity.findViewById<TextView>(R.id.binding_status).text = PairingRuntime.status
        for (id in listOf(R.id.pair_device, R.id.recover_pairing, R.id.cancel_pairing))
            activity.findViewById<Button>(id).isEnabled = !PairingRuntime.busy
    }
    private fun input() {
        val form = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 8) }
        val origin = EditText(activity).apply { hint = "https://网关地址:端口"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI; isSingleLine = true; isSaveEnabled = false }
        val token = EditText(activity).apply { hint = "短期配对授权"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; isSingleLine = true; isSaveEnabled = false }
        form.addView(origin); form.addView(token)
        val dialog = AlertDialog.Builder(activity).setTitle("连接收款观察网关").setView(form)
            .setNegativeButton("取消", null).setPositiveButton("查看授权") { _, _ ->
                val address = origin.text.toString().trim(); val credential = token.text.toString().trim()
                token.text.clear()
                PairingRuntime.inspect(address, credential) { confirmation ->
                    if (!activity.isFinishing && !activity.isDestroyed) confirm(confirmation)
                }
            }.create()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
    }
    private fun confirm(value: PairingConfirmation) {
        val scope = value.scope
        val source = if (scope.sourceClass.wire == "synthetic") "隔离合成测试" else "通知观察"
        val action = if (scope.mode == "rotate") "轮换当前凭据" else "建立新绑定并替代旧设备"
        AlertDialog.Builder(activity).setTitle("确认观察范围")
            .setMessage("网关：${scope.origin}\n环境：${scope.environment}\n收款范围：${scope.receiverId}\n渠道：${scope.provider}\n来源：$source\n操作：$action\n\n通知只作为观察证据。历史未绑定事件不会上传。")
            .setNegativeButton("取消", null).setPositiveButton("确认配对") { _, _ -> PairingRuntime.confirm(value) }.show()
    }
}
