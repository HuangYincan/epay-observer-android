package dev.epay.observer

import dev.epay.observer.capture.*
import dev.epay.observer.parsing.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class FixtureParserCorpusTest(private val name: String, private val row: JSONObject) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun cases(): List<Array<Any>> {
            val text = checkNotNull(FixtureParserCorpusTest::class.java.getResourceAsStream("/synthetic-cases.json"))
                .bufferedReader().use { it.readText() }
            val cases = JSONArray(text)
            return (0 until cases.length()).map { val row = cases.getJSONObject(it); arrayOf(row.getString("id"), row) }
        }
    }
    @Test fun classifiesWithoutInventingPaymentIdentity() {
        val f = row.getJSONObject("fields")
        fun optional(key: String): String? = if (f.isNull(key)) null else f.getString(key)
        val fields = NotificationFields(optional("title"), optional("text"), optional("bigText"),
            optional("channel"), optional("category"), f.getInt("flags"), optional("group"),
            f.getBoolean("unsupported"), optional("template"), f.getBoolean("hasAdditionalText"))
        val source = SourceIdentity(row.optString("package", "dev.epay.observer.fixture"), row.optLong("user", 0),
            row.optLong("version", 1), if (row.optBoolean("emptyCerts")) emptySet() else setOf("synthetic-metadata"),
            row.optString("opPackage", "dev.epay.observer.fixture"))
        val restored = row.optBoolean("restored")
        val observation = NotificationObservation(ObservationInput("synthetic-key", source, 100, 200, 300, restored, fields),
            200, 300, revision = row.optInt("revision", 1), uncertain = true, everRestored = restored, removed = row.optBoolean("removed"))
        val provider = Provider.valueOf(row.getString("provider"))
        val enabled = Provider.entries.toSet() - if (row.optBoolean("disabled")) setOf(provider) else emptySet()
        val result = ParserRegistry.analyze(observation, enabled)
        when (row.getString("expected")) {
            "Candidate" -> {
                assertTrue(name, result is ParseResult.Candidate)
                result as ParseResult.Candidate
                assertEquals(provider, result.provider)
                assertEquals(row.getLong("amountMinor"), result.amountMinor)
                assertEquals("CNY", result.currency)
                assertEquals(row.getString("ruleVersion"), result.ruleVersion)
                assertTrue(result.identityUncertain)
            }
            "Ignored" -> assertEquals(name, ParseResult.Ignored(ParseReason.valueOf(row.getString("reason"))), result)
            "Unsupported" -> assertEquals(name, ParseResult.Unsupported(ParseReason.valueOf(row.getString("reason"))), result)
            else -> fail("Unknown expected outcome in corpus")
        }
    }
}
