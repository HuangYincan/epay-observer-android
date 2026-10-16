package dev.epay.observer

import dev.epay.observer.status.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DiagnosticReportTest {
    private fun snapshot(counts: Map<String, Int>) = DiagnosticSnapshot("0.1.0", 37, true,
        true, false, true, false, 4, 3, 1, false, false, counts, true)

    @Test fun reportHasOnlyExplicitAggregateFieldsAndNoBusinessSuccessClaim() {
        val json = JSONObject(DiagnosticReport.encode(snapshot(mapOf("ACKED" to 2, "READY" to 1))))
        assertEquals(setOf("schema_version", "scope", "app_version", "android_api", "mode", "notification_permission",
            "listener_connected", "local_bindings", "storage", "delivery_counts", "has_durable_ack",
            "payment_status", "merchant_notification_status"), json.keys().asSequence().toSet())
        assertEquals("aggregate-only", json.getString("scope"))
        assertEquals("not_queried", json.getString("payment_status"))
        assertEquals("not_queried", json.getString("merchant_notification_status"))
        assertTrue(json.getBoolean("has_durable_ack"))
        assertFalse(json.getBoolean("listener_connected"))
        assertEquals(setOf("wxpay", "alipay"), json.getJSONObject("local_bindings").keys().asSequence().toSet())
    }
    @Test fun unrecognizedStateCannotLeakThroughDynamicJsonKeys() {
        val report = DiagnosticReport.encode(snapshot(mapOf("READY" to 2,
            "raw-notification-secret-device-id-key" to 4, "https://private-gateway.example" to 3)))
        assertFalse(report.contains("secret")); assertFalse(report.contains("private-gateway"))
        val counts = JSONObject(report).getJSONObject("delivery_counts")
        assertEquals((DiagnosticReport.states + "OTHER").toSet(), counts.keys().asSequence().toSet())
        assertEquals(7, counts.getInt("OTHER"))
    }
    @Test fun localConflictOverridesOldAckAndUnknownValuesStayFixed() {
        assertEquals("通知身份冲突，停止自动处理", EventStatusText.state("IDENTITY_CONFLICT", "ACKED"))
        assertEquals("采集时未绑定，不会补绑上传", EventStatusText.state("UNBOUND", null))
        assertEquals("网关已接收（ACK）", EventStatusText.state("QUEUED", "ACKED"))
        for (state in listOf("READY", "RETRY", "IN_FLIGHT", "HELD", "TERMINAL")) {
            assertFalse(EventStatusText.state("QUEUED", state).contains("充值成功"))
        }
        assertEquals("未知状态，需检查", EventStatusText.state("secret", "raw text"))
        assertEquals("未知原因，需检查", EventStatusText.reason("secret-key"))
    }
}
