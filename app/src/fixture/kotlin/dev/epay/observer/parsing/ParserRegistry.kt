package dev.epay.observer.parsing

import dev.epay.observer.capture.NotificationFields
import dev.epay.observer.capture.NotificationObservation

object ParserRegistry {
    const val available = true
    private val rules = listOf(FixtureWechatParser, FixtureAlipayParser)

    fun analyze(observation: NotificationObservation, enabled: Set<Provider>): ParseResult {
        val source = observation.latest.source
        if (source.packageName != "dev.epay.observer.fixture" || source.opPackage != source.packageName ||
            source.user < 0 || source.version != 1L || source.signers.isEmpty()) return unsupported(ParseReason.SOURCE)
        if (observation.removed) return ignored(ParseReason.REMOVED)
        if (observation.everRestored || observation.latest.restored) return ignored(ParseReason.RESTORED)
        if (observation.revision > 1) return unsupported(ParseReason.REVISION)
        val fields = observation.latest.fields
        if (fields.unsupported) return unsupported(ParseReason.MALFORMED)
        if (fields.flags and 0x200 != 0 || fields.group != null) return ignored(ParseReason.GROUP)
        if (fields.category == "msg") return ignored(ParseReason.CHAT)
        when (fields.title) {
            "合成聊天" -> return ignored(ParseReason.CHAT)
            "合成退款" -> return ignored(ParseReason.REFUND)
            "合成支出" -> return ignored(ParseReason.EXPENSE)
            "合成推广" -> return ignored(ParseReason.PROMOTION)
            "合成聚合" -> return ignored(ParseReason.AGGREGATE)
        }
        if (fields.hasAdditionalText) return unsupported(ParseReason.ADDITIONAL_TEXT)
        if (fields.channel != "synthetic-v1" || fields.category != "status" ||
            fields.template != "android.app.Notification\$BigTextStyle") return unsupported(ParseReason.STRUCTURE)
        val rule = rules.singleOrNull { it.title == fields.title } ?: return unsupported(ParseReason.TITLE)
        if (rule.provider !in enabled) return ignored(ParseReason.DISABLED)
        return rule.parse(fields)
    }

    private fun ignored(reason: ParseReason) = ParseResult.Ignored(reason)
    private fun unsupported(reason: ParseReason) = ParseResult.Unsupported(reason)
}

/** Independent fixture descriptors; not historical or current payment-app compatibility rules. */
internal object FixtureWechatParser : FixtureReceiptRule(
    Provider.WXPAY, "微信形态 · 合成收款", "收款", "fixture-wx-v1",
)
internal object FixtureAlipayParser : FixtureReceiptRule(
    Provider.ALIPAY, "支付宝形态 · 合成到账", "到账", "fixture-ali-v1",
)
internal abstract class FixtureReceiptRule(
    val provider: Provider, val title: String, private val verb: String, private val version: String,
) {
    private val body = Regex("${Regex.escape(verb)} ([^ ]+) ([^ ]+)")
    fun parse(fields: NotificationFields): ParseResult {
        val texts = listOfNotNull(fields.text, fields.bigText)
        if (texts.isEmpty()) return ParseResult.Unsupported(ParseReason.MISSING_TEXT)
        val amounts = mutableListOf<Long>()
        for (text in texts) {
            if (text.startsWith("退款 ")) return ParseResult.Ignored(ParseReason.REFUND)
            if (text.startsWith("付款 ") || text.startsWith("转出 ")) return ParseResult.Ignored(ParseReason.EXPENSE)
            val match = body.matchEntire(text) ?: return ParseResult.Unsupported(ParseReason.TEXT)
            if (match.groupValues[2] != "元") return ParseResult.Unsupported(ParseReason.CURRENCY)
            val amount = MinorAmount.parse(match.groupValues[1]) ?: return ParseResult.Unsupported(ParseReason.AMOUNT)
            amounts.add(amount)
        }
        if (amounts.distinct().size != 1) return ParseResult.Unsupported(ParseReason.CONFLICT)
        return ParseResult.Candidate(provider, amounts.first(), "CNY", version)
    }
}
