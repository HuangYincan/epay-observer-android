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
}
