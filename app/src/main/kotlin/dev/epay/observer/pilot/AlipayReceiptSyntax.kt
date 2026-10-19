package dev.epay.observer.pilot

import dev.epay.observer.capture.NotificationObservation
import dev.epay.observer.parsing.*

/** Syntax derived from redacted research. Only a separately reviewed permit can use it. */
object AlipayReceiptSyntax {
    const val VERSION = "alipay-12.12.30-receipt-v1"
    private val title = Regex("你已成功收款((?:0|[1-9][0-9]{0,9})(?:\\.[0-9]{1,2})?)元（([^（）]{1,64})）")
    private const val TEXT_SHAPE = "<text>转入余额宝 <text><number><text><number><text>"
    // Keep the finite public vocabulary consistent with SPEC-038's private redactor.
    private val words = setOf("支付宝", "收钱码", "二维码", "你已", "通过", "扫码", "成功", "收款", "付款",
        "转账", "到账", "收入", "人民币", "金额", "元", "扫码付款", "扫码收款", "二维码收款", "二维码付款",
        "收钱码收款", "个人收款", "商家收款", "商家服务", "当面付", "转账收款", "余额", "余额宝",
        "支付宝余额", "花呗", "银行卡", "信用卡", "储蓄卡", "红包", "集分宝", "网商银行", "余额付款",
        "余额宝付款", "花呗付款", "银行卡付款", "收款提示", "付款方", "付款人", "收款方", "收款人",
        "来自", "对方", "留言", "备注", "实名", "姓名", "昵称", "匿名用户", "顾客", "买家", "向你", "转入")
    private val token = Regex(words.sortedWith(compareByDescending<String> { it.length }.thenBy { it })
        .joinToString("|", transform = Regex::escape) + "|[0-9]+(?:[.．][0-9]+)?|[，。:：.,￥¥()（）\\- ]")

    private fun clean(value: String): Boolean = value.none {
        Character.getType(it) in setOf(Character.CONTROL.toInt(), Character.FORMAT.toInt(),
            Character.SURROGATE.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt())
    }
    private fun shape(value: String): String = buildString {
        var end = 0
        token.findAll(value).forEach { match ->
            if (match.range.first > end) append("<text>")
            append(if (match.value.first() in '0'..'9') "<number>" else match.value)
            end = match.range.last + 1
        }
        if (end < value.length) append("<text>")
    }

    fun analyze(observation: NotificationObservation, enabled: Set<Provider>): ParseResult {
        val input = observation.latest
        val permit = input.pilotPermit ?: return ParseResult.Unsupported(ParseReason.NO_VERIFIED_RULE)
        if (!permit.configuration.review.complete() || !permit.matches(input) || !PilotAlipaySource.accepts(input.source))
            return ParseResult.Unsupported(ParseReason.SOURCE)
        if (Provider.ALIPAY !in enabled) return ParseResult.Ignored(ParseReason.DISABLED)
        if (observation.removed) return ParseResult.Ignored(ParseReason.REMOVED)
        if (observation.everRestored || input.restored) return ParseResult.Unsupported(ParseReason.RESTORED)
        if (observation.revision != 1) return ParseResult.Unsupported(ParseReason.REVISION)
        val fields = input.fields
        if (fields.unsupported || listOf(fields.title, fields.text, fields.bigText, fields.channel, fields.category,
                fields.group, fields.template).any { it != null && (it.length > 512 || !clean(it)) })
            return ParseResult.Unsupported(ParseReason.MALFORMED)
        if (fields.flags and 0x200 != 0) return ParseResult.Ignored(ParseReason.GROUP)
        if (fields.category == "msg" || fields.template?.contains("MessagingStyle") == true)
            return ParseResult.Ignored(ParseReason.CHAT)
        if (fields.flags != 0x10 || fields.group.isNullOrEmpty() || fields.channel.isNullOrEmpty() ||
            fields.category != null || fields.template != null || fields.bigText != null)
            return ParseResult.Unsupported(ParseReason.STRUCTURE)
        if (fields.hasAdditionalText) return ParseResult.Unsupported(ParseReason.ADDITIONAL_TEXT)
        val match = fields.title?.let(title::matchEntire) ?: return ParseResult.Unsupported(ParseReason.TITLE)
        val amount = MinorAmount.parse(match.groupValues[1]) ?: return ParseResult.Unsupported(ParseReason.AMOUNT)
        if (amount != permit.configuration.amountMinor) return ParseResult.Unsupported(ParseReason.AMOUNT)
        val text = fields.text?.takeIf { it.isNotEmpty() } ?: return ParseResult.Unsupported(ParseReason.MISSING_TEXT)
        if (shape(text) != TEXT_SHAPE) return ParseResult.Unsupported(ParseReason.TEXT)
        return ParseResult.Candidate(Provider.ALIPAY, amount, "CNY", VERSION, permit)
    }
}
