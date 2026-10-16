package dev.epay.observer

import dev.epay.observer.capture.*
import dev.epay.observer.parsing.*
import dev.epay.observer.storage.*
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SyntheticEvidenceIdentityTest {
    private val input = ObservationInput("first-key", SourceIdentity("dev.epay.observer.fixture", 0, 1, setOf("synthetic")),
        1792022399000, 1792022399000, 1000, false,
        NotificationFields("微信形态 · 合成收款", "收款 10.00 元", "收款 10.00 元", "synthetic-v1", "status", 0, null,
            template = "android.app.Notification\$BigTextStyle"))
    @Test fun realParserQualificationEncoderAndSignerShareBothProviderGoldens() {
        val vectors = requireNotNull(javaClass.getResourceAsStream("/vectors.json")).use { JSONObject(it.bufferedReader().readText()).getJSONArray("vectors") }
        for (provider in Provider.entries) {
            val capture = if (provider == Provider.WXPAY) input else input.copy(fields = input.fields.copy(
                title = "支付宝形态 · 合成到账", text = "到账 10.00 元", bigText = "到账 10.00 元"))
            val observation = NotificationObservation(capture, capture.wallTime, capture.elapsedTime, uncertain = true, everRestored = false)
            val candidate = ParserRegistry.analyze(observation, Provider.entries.toSet()) as ParseResult.Candidate
            assertTrue(candidate.identityUncertain)
            val suffix = if (provider == Provider.WXPAY) "wx" else "ali"
            val binding = BindingSnapshot("https://observer.example", "test", "fixture-device-a", "fixture-receiver-$suffix", 1, provider, BindingSnapshot.SourceClass.SYNTHETIC)
            val identity = OutboundPolicy.identityForNewObservation(capture, candidate, binding, false)
            assertEquals(EvidenceIdentity.DISTINCT, identity)
            val vector = (0 until vectors.length()).map(vectors::getJSONObject).single { it.getString("id") == provider.name.lowercase()+"-android-distinct" }
            val actual = EventWire.encode("11111111-1111-4111-8111-111111111111", candidate, capture.wallTime, binding, identity)
            assertArrayEquals(Base64.getDecoder().decode(vector.getString("body_base64")), actual)
            for (changed in listOf(capture.copy(restored = true),capture.copy(postTime = capture.wallTime+1),
                capture.copy(postTime = capture.wallTime-120001),capture.copy(postTime = -1))) {
                assertEquals(EvidenceIdentity.UNCERTAIN, OutboundPolicy.identityForNewObservation(changed,candidate,binding,false))
            }
            assertEquals(EvidenceIdentity.UNCERTAIN, OutboundPolicy.identityForNewObservation(capture,candidate,binding,true))
            assertEquals(EvidenceIdentity.UNCERTAIN, OutboundPolicy.identityForNewObservation(capture,candidate,binding.copy(sourceClass=BindingSnapshot.SourceClass.NOTIFICATION),false))
            assertEquals(EvidenceIdentity.UNCERTAIN, OutboundPolicy.identityForNewObservation(capture,candidate.copy(ruleVersion="unknown"),binding,false))
        }
    }
}
