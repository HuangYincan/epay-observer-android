package dev.epay.observer.parsing

enum class Provider { WXPAY, ALIPAY }
enum class ParseReason(val description: String) {
    NO_VERIFIED_RULE("尚无已验证规则"), SOURCE("来源元数据不符"), DISABLED("通道已关闭"),
    REMOVED("通知已撤下"), RESTORED("恢复的旧通知"), REVISION("通知发生修订，身份待确认"),
    MALFORMED("字段类型或长度异常"), GROUP("摘要或分组通知"), CHAT("聊天通知"),
    REFUND("退款通知"), EXPENSE("支出通知"), PROMOTION("推广通知"), AGGREGATE("聚合通知"),
    STRUCTURE("通知样式未验证"), ADDITIONAL_TEXT("包含未支持的额外内容"),
    TITLE("标题未验证"), MISSING_TEXT("缺少正文"), TEXT("正文不符合规则"),
    AMOUNT("金额格式或范围不符"), CURRENCY("币种不支持"), CONFLICT("多字段金额冲突"),
}
sealed interface ParseResult {
    /** Lexical candidate only. No transaction identity, payment proof or delivery side effects. */
    data class Candidate(val provider: Provider, val amountMinor: Long, val currency: String, val ruleVersion: String,
                         val pilotPermit: dev.epay.observer.pilot.PilotPermit? = null) : ParseResult {
        val identityUncertain: Boolean get() = true
    }
    data class Ignored(val reason: ParseReason) : ParseResult
    data class Unsupported(val reason: ParseReason) : ParseResult
}

object MinorAmount {
    const val MAX: Long = 999_999_999_999L
    private val grammar = Regex("(?:0|[1-9][0-9]{0,9})(?:\\.[0-9]{1,2})?")
    fun parse(value: String): Long? {
        if (!grammar.matches(value)) return null
        val parts = value.split('.')
        val whole = parts[0].toLongOrNull() ?: return null
        val fraction = if (parts.size == 1) 0 else parts[1].padEnd(2, '0').toInt()
        val minor = whole * 100 + fraction
        return minor.takeIf { it in 1..MAX }
    }
}
