package dev.epay.observer

import dev.epay.observer.capture.*
import dev.epay.observer.parsing.*
import dev.epay.observer.pilot.AlipayReceiptSyntax
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ReceiptFieldResearchTest {
    private val receipt = NotificationFields("你已成功收款12.34元（合成）", "甲转入余额宝 乙1丙2丁",
        null, "channel", null, 16, "group")

    @Test fun researchDoesNotGrantProductionAuthority() {
        assertEquals(1234L, AlipayReceiptSyntax.inspectFields(receipt).amountMinor)
        val source = SourceIdentity("com.eg.android.AlipayGphone", 0, 212310, emptySet())
        val input = ObservationInput("synthetic", source, 0, 0, 0, false, receipt)
        val observed = NotificationObservation(input, 0, 0, uncertain = true, everRestored = false)
        assertEquals(ParseResult.Unsupported(ParseReason.NO_VERIFIED_RULE), AlipayReceiptSyntax.analyze(observed, setOf(Provider.ALIPAY)))
        assertEquals(ParseReason.AMOUNT, AlipayReceiptSyntax.inspectFields(receipt, 1).reason)
        assertEquals(ParseReason.CHAT, AlipayReceiptSyntax.inspectFields(receipt.copy(category = "msg")).reason)
        assertEquals(ParseReason.ADDITIONAL_TEXT, AlipayReceiptSyntax.inspectFields(receipt.copy(hasAdditionalText = true)).reason)
    }

    /** Opt-in private field research; never creates a permit or observation for real input. */
    @Test fun inspectPrivateComparisonWhenExplicitlyProvided() {
        val path = System.getenv("OBSERVER_COMPARISON_RAW")
        assumeTrue(path != null)
        val file = File(requireNotNull(path))
        require(file.isFile && file.length() <= 32768)
        val rows = JSONArray(file.readText())
        require(rows.length() in 0..2)
        val results = JSONArray()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            fun text(key: String): String? = if (row.isNull(key)) null else row.getString(key)
            val style = row.getString("style")
            val category = row.getString("category")
            val extra = row.getJSONObject("additional")
            val fields = NotificationFields(text("title"), text("text"), text("big_text"),
                if (row.getBoolean("channel_present")) "private-present" else null,
                if (category == "none") null else category, row.getInt("flags"),
                if (row.getBoolean("group_present")) "private-present" else null,
                unsupported = !row.getBoolean("presentation_valid"),
                template = if (style == "none") null else "android.app.Notification\$$style",
                hasAdditionalText = extra.keys().asSequence().any { extra.getBoolean(it) })
            val result = AlipayReceiptSyntax.inspectFields(fields)
            results.put(JSONObject().put("kind", row.getString("kind"))
                .put("syntax_matches", result.amountMinor != null)
                .put("reason", result.reason?.name ?: "SYNTAX_ONLY")
                .put("payment_eligibility", false))
        }
        File(file.parentFile, "parser-results.json").apply {
            require(!exists())
            writeText(results.toString())
        }
    }
}
