package dev.epay.observer

import dev.epay.observer.pilot.*
import org.junit.Assert.*
import org.junit.Test

class PilotConfigurationCodecTest {
    private val input = """{"schema_version":2,"origin":"https://observer.example","environment":"test","device_id":"device","receiver_id":"receiver","binding_version":1,"provider":"alipay","source_class":"notification","parser_version":"alipay-12.12.30-receipt-v1","amount_minor":1234,"server_armed_at_ms":100000,"server_expires_at_ms":400000,"evidence_ref":"synthetic-test-only","template_verified":1,"chat_boundary_verified":1,"identity_policy_approved":1,"amount_policy_approved":1}"""
    @Test fun gatewayOperatorGoldenHandoffDecodesWithoutOrderHints() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/handoff.json")).use { it.readBytes() }
        val decoded = PilotConfigurationCodec.decode(bytes)
        assertEquals(1234L, decoded.amountMinor)
        assertEquals("test-device", decoded.binding.deviceId)
        assertEquals(100000L, decoded.serverArmedAt)
        assertEquals(400000L, decoded.serverExpiresAt)
        assertTrue(decoded.review.complete())
        assertFalse(PilotRuntime.hasSession)
    }
    @Test fun strictHandoffDecodesAndEachUnreviewedFactFailsClosed() {
        assertEquals(1234L,PilotConfigurationCodec.decode(input.toByteArray()).amountMinor)
        for (name in listOf("template_verified","chat_boundary_verified","identity_policy_approved","amount_policy_approved")) {
            val changed = input.replace("\"$name\":1","\"$name\":0")
            assertTrue(runCatching { PilotConfigurationCodec.decode(changed.toByteArray()) }.isFailure)
            val missing = input.replace(",\"$name\":1", "")
            assertTrue(runCatching { PilotConfigurationCodec.decode(missing.toByteArray()) }.isFailure)
        }
    }
    @Test fun oldAndMixedMerchantHandoffsAreRejectedWithoutArming() {
        val old = input.replace("\"schema_version\":2", "\"schema_version\":1")
            .replace("}", ",\"newapi_contract_verified\":1}")
        for (changed in listOf(old,
            input.replace("\"schema_version\":2", "\"schema_version\":1"),
            input.replace("}", ",\"newapi_contract_verified\":1}"),
            input.replace("}", ",\"merchant_callback_verified\":1}"))) {
            assertTrue(runCatching { PilotConfigurationCodec.decode(changed.toByteArray()) }.isFailure)
        }
        assertFalse(PilotRuntime.hasSession)
    }
    @Test fun duplicateUnknownFractionalAndCrossSourceValuesAreRejected() {
        for (changed in listOf(input.replace("{","{\"schema_version\":1,"),
            input.replace("}",",\"order_id\":123}"),input.replace("1234","12.34"),
            input.replace("notification","synthetic"),input.replace("alipay-12.12.30-receipt-v1","unverified-rule"),
            input.replace("\"alipay\"","\"wxpay\""),input+"trailing",input.replace("\"template_verified\":1","\"template_verified\":true"))) {
            assertTrue(runCatching { PilotConfigurationCodec.decode(changed.toByteArray()) }.isFailure)
        }
    }
    @Test fun fixtureAndReleaseBuildsCannotArmEvenWithCompleteSyntheticReview() {
        if (!PilotBuild.allowed) {
            assertTrue(runCatching { PilotRuntime.arm(PilotConfigurationCodec.decode(input.toByteArray()),100_000,10_000) }.isFailure)
        }
        assertFalse(PilotRuntime.hasSession)
    }
}
