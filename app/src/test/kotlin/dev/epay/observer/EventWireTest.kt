package dev.epay.observer

import dev.epay.observer.parsing.*
import dev.epay.observer.storage.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EventWireTest {
    private val binding = BindingSnapshot("https://observer.example", "isolated-test", "device-test", "receiver-test", 1,
        Provider.WXPAY, BindingSnapshot.SourceClass.SYNTHETIC)
    @Test fun validatesOriginAndScopeWithoutEmbeddingSecretsOrOrders() {
        for (origin in listOf("http://example.com", "https://user:pass@example.com", "https://example.com/", "https://example.com?q=1", "https://example.com#x", "https://EXAMPLE.com", "https://example.com:0", "https://example.com:65536")) {
            assertTrue(origin, runCatching { binding.copy(origin = origin) }.isFailure)
        }
        assertTrue(runCatching { binding.copy(deviceId = "unsafe\"") }.isFailure)
        assertTrue(runCatching { binding.copy(version = 0) }.isFailure)
        assertNotEquals(binding.scope, binding.copy(environment = "other").scope)
        assertNotEquals(binding.scope, binding.copy(version = 2).scope)
        assertNotEquals(binding.scope, binding.copy(receiverId = "other").scope)
        assertNotEquals(binding.scope, binding.copy(origin = "https://other.example").scope)
        assertEquals(binding.scope, binding.copy().scope)
    }
    @Test fun exactWireUsesIntegerMinorUnitsAndNoFabricatedPaymentIdentity() {
        val id = "11111111-1111-4111-8111-111111111111"
        val candidate = ParseResult.Candidate(Provider.WXPAY, 999_999_999_999, "CNY", "fixture-wx-v1")
        val bytes = EventWire.encode(id, candidate, 1000, binding)
        val parsed = JSONObject(bytes.toString(Charsets.UTF_8))
        assertEquals(setOf("schema_version", "event_id", "device_id", "receiver_id", "binding_version", "provider", "amount_minor", "currency", "observed_at_ms", "parser_version", "source_class", "identity_confidence"), parsed.keys().asSequence().toSet())
        assertEquals(999_999_999_999, parsed.getLong("amount_minor"))
        assertEquals("uncertain", parsed.getString("identity_confidence"))
        assertEquals("synthetic", parsed.getString("source_class"))
        assertArrayEquals(bytes, EventWire.encode(id, candidate, 1000, binding))
        assertTrue(runCatching { EventWire.encode(id, candidate, 1000, binding.copy(provider = Provider.ALIPAY)) }.isFailure)
    }
}
