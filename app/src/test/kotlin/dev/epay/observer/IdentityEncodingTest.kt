package dev.epay.observer

import dev.epay.observer.capture.*
import dev.epay.observer.parsing.*
import dev.epay.observer.storage.IdentityEncoding
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class IdentityEncodingTest {
    @Test fun lengthPrefixesPreventAmbiguousIdentities() {
        assertNotEquals(IdentityEncoding.digest("ab", "c"), IdentityEncoding.digest("a", "bc"))
        assertNotEquals(IdentityEncoding.digest(null), IdentityEncoding.digest(""))
        assertNotEquals(IdentityEncoding.identity("epoch", 0, "package", "key-a"), IdentityEncoding.identity("epoch", 0, "package", "key-b"))
        assertNotEquals(IdentityEncoding.identity("epoch", 0, "package", "key"), IdentityEncoding.identity("epoch", 10, "package", "key"))
    }
    @Test fun signerOrderIsStableButSourceChangeIsNot() {
        val a = SourceIdentity("package", 0, 1, linkedSetOf("a", "b"))
        assertEquals(IdentityEncoding.source(a), IdentityEncoding.source(a.copy(signers = linkedSetOf("b", "a"))))
        assertNotEquals(IdentityEncoding.source(a), IdentityEncoding.source(a.copy(version = 2)))
        assertNotEquals(IdentityEncoding.source(a), IdentityEncoding.source(a.copy(opPackage = "delegated")))
    }
    @Test fun localDraftDoesNotPretendToBeSendableProtocolBody() {
        val id = "11111111-1111-4111-8111-111111111111"
        val candidate = ParseResult.Candidate(Provider.WXPAY, 1001, "CNY", "synthetic-rule-v1")
        val first = IdentityEncoding.localPayload(id, candidate, 1234)
        assertArrayEquals(first, IdentityEncoding.localPayload(id, candidate, 1234))
        val json = JSONObject(first.toString(Charsets.UTF_8))
        assertEquals("local-candidate-v1", json.getString("kind"))
        assertEquals(1001, json.getLong("amount_minor"))
        assertEquals(1234, json.getLong("observed_at_ms"))
        assertEquals("uncertain", json.getString("identity_confidence"))
        for (field in listOf("device_id", "receiver_id", "binding_version", "provider_transaction_id", "order_id", "text", "key")) assertFalse(json.has(field))
    }
    @Test fun malformedDraftFieldsCannotEscapeJsonOrLoseIntegerPrecision() {
        val id = "11111111-1111-4111-8111-111111111111"
        val candidate = ParseResult.Candidate(Provider.ALIPAY, 999999999999, "CNY", "synthetic-rule-v1")
        assertEquals(999999999999L, JSONObject(IdentityEncoding.localPayload(id, candidate, 0).toString(Charsets.UTF_8)).getLong("amount_minor"))
        for (invalid in listOf(candidate.copy(ruleVersion = "a\"b"), candidate.copy(amountMinor = 0), candidate.copy(currency = "USD"))) {
            assertTrue(runCatching { IdentityEncoding.localPayload(id, invalid, 0) }.isFailure)
        }
        assertTrue(runCatching { IdentityEncoding.localPayload(id, candidate, -1) }.isFailure)
    }
}
