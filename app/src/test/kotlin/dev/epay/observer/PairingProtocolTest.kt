package dev.epay.observer

import dev.epay.observer.protocol.PairingProtocol
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PairingProtocolTest {
    @Test fun sharedPairingRequests() {
        val data = javaClass.classLoader!!.getResourceAsStream("pairing.json")!!.bufferedReader().use { it.readText() }
        val cases = JSONObject(data).getJSONArray("requests")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val valid = runCatching { PairingProtocol.validateRequest(case.getString("body").toByteArray(), case.getString("operation")) }.isSuccess
            assertEquals(case.getString("id"), case.getBoolean("valid"), valid)
        }
    }
    @Test fun errorsAreStrictAndSecretsAreNotRendered() {
        assertEquals("invalid_grant", PairingProtocol.error("{\"schema_version\":1,\"code\":\"invalid_grant\"}".toByteArray()))
        for (value in listOf("{}", "{\"schema_version\":1,\"code\":\"sql_password\"}", "{\"schema_version\":1,\"code\":\"unavailable\",\"secret\":\"x\"}")) {
            assertTrue(runCatching { PairingProtocol.error(value.toByteArray()) }.isFailure)
        }
        val request = PairingProtocol.newRequest()
        assertTrue(PairingProtocol.validSecret(request.resumeSecret))
        assertFalse(request.toString().contains(request.resumeSecret))
    }
    @Test fun enrollmentMustMatchEveryConfirmedScopeField() {
        val scopeJson = JSONObject("""{"schema_version":1,"grant_id":"grant","origin":"https://observer.example","environment":"test","receiver_id":"receiver","provider":"wxpay","source_class":"synthetic","mode":"replace","expected_binding_version":1,"device_id":"","expires_at_ms":10000}""")
        val scope = PairingProtocol.scope(scopeJson.toString().toByteArray(), "https://observer.example")
        val request = PairingProtocol.newRequest()
        val response = JSONObject("""{"schema_version":1,"origin":"https://observer.example","environment":"test","receiver_id":"receiver","provider":"wxpay","source_class":"synthetic","device_id":"device","binding_version":2,"key_id":"key","not_before_ms":1000,"expires_at_ms":2000}""")
            .put("enrollment_id", request.enrollmentId).put("key_base64url", request.resumeSecret)
        PairingProtocol.enrollment(response.toString().toByteArray(), scope, request).material.fill(0)
        for ((field, wrong) in listOf("origin" to "https://other.example", "environment" to "other", "receiver_id" to "other",
            "provider" to "alipay", "source_class" to "notification", "binding_version" to 1, "enrollment_id" to java.util.UUID.randomUUID().toString(),
            "key_base64url" to request.resumeSecret + "=", "expires_at_ms" to 1000)) {
            val changed = JSONObject(response.toString()).put(field, wrong)
            assertTrue(field, runCatching { PairingProtocol.enrollment(changed.toString().toByteArray(), scope, request) }.isFailure)
        }
        assertTrue(runCatching { PairingProtocol.scope(scopeJson.toString().toByteArray(), "https://other.example") }.isFailure)
    }
}
