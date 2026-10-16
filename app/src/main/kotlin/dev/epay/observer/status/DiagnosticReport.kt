package dev.epay.observer.status

import org.json.JSONObject

/** Input contains aggregates only. Never accept a row, exception, body or credential here. */
data class DiagnosticSnapshot(val version: String, val apiLevel: Int, val fixture: Boolean,
    val permission: Boolean, val listenerConnected: Boolean, val wxBound: Boolean, val aliBound: Boolean,
    val observations: Int, val events: Int, val conflicts: Int, val captureGap: Boolean,
    val capacityBlocked: Boolean, val deliveryCounts: Map<String, Int>, val hasAck: Boolean)

object DiagnosticReport {
    val states = listOf("READY", "RETRY", "IN_FLIGHT", "ACKED", "HELD", "TERMINAL")
    fun encode(value: DiagnosticSnapshot): String {
        val counts = JSONObject()
        states.forEach { counts.put(it, (value.deliveryCounts[it] ?: 0).coerceAtLeast(0)) }
        counts.put("OTHER", value.deliveryCounts.filterKeys { it !in states }.values.sumOf { it.coerceAtLeast(0).toLong() })
        return JSONObject().put("schema_version", 1).put("scope", "aggregate-only")
            .put("app_version", value.version).put("android_api", value.apiLevel)
            .put("mode", if (value.fixture) "synthetic-test" else "production")
            .put("notification_permission", value.permission).put("listener_connected", value.listenerConnected)
            .put("local_bindings", JSONObject().put("wxpay", value.wxBound).put("alipay", value.aliBound))
            .put("storage", JSONObject().put("observations", value.observations).put("events", value.events)
                .put("conflicts", value.conflicts).put("capture_gap", value.captureGap).put("capacity_blocked", value.capacityBlocked))
            .put("delivery_counts", counts).put("has_durable_ack", value.hasAck)
            .put("payment_status", "not_queried").put("merchant_notification_status", "not_queried")
            .toString(2)
    }
}

object EventStatusText {
    fun state(local: String, delivery: String?): String = when {
        local == "UNBOUND" -> "采集时未绑定，不会补绑上传"
        local == "IDENTITY_CONFLICT" -> "通知身份冲突，停止自动处理"
        delivery == "READY" -> "等待发送"
        delivery == "RETRY" -> "等待重试"
        delivery == "IN_FLIGHT" -> "发送中"
        delivery == "ACKED" -> "网关已接收（ACK）"
        delivery == "HELD" -> "已暂停，证据保留"
        delivery == "TERMINAL" -> "协议拒绝，证据保留"
        else -> "未知状态，需检查"
    }
    fun reason(code: String?): String? = when (code) {
        null -> null
        "AUTHENTICATION" -> "请修复凭据或设备时间"
        "CLOCK_RANGE" -> "请检查时间或凭据有效期"
        "SCOPE", "BINDING_CHANGED", "BINDING_REVOKED" -> "请核对授权范围或重新配对"
        "PAIRING_IN_PROGRESS" -> "配对尚未完成"
        "CONFLICT", "IDENTITY_CONFLICT" -> "证据冲突，不能靠重试解除"
        "REDIRECT" -> "拒绝网关重定向"
        "UNEXPECTED_RESPONSE" -> "网关响应无法确认"
        "INVALID_PAYLOAD", "UNSUPPORTED_VERSION", "BODY_TOO_LARGE", "UNSUPPORTED_MEDIA_TYPE" -> "协议不兼容，请更新后核对"
        "ATTEMPT_LIMIT" -> "已达到尝试上限"
        else -> "未知原因，需检查"
    }
}
