package dev.epay.observer.parsing

import android.app.Activity
import android.widget.CheckBox
import android.widget.TextView
import dev.epay.observer.R
import dev.epay.observer.capture.CaptureState

/** A read-only interpretation of retained observations, not an event-producing pipeline. */
class ParserPreview(private val activity: Activity) {
    private val preferences = ParserPreferences(activity)
    private var enabled = preferences.enabled()
    init {
        for ((id, provider) in listOf(R.id.wx_parser to Provider.WXPAY, R.id.ali_parser to Provider.ALIPAY)) {
            activity.findViewById<CheckBox>(id).apply {
                isChecked = ParserRegistry.available && provider in enabled
                isEnabled = ParserRegistry.available
                setOnCheckedChangeListener { _, checked ->
                    enabled = if (checked) enabled + provider else enabled - provider
                    preferences.setEnabled(provider, checked)
                    render()
                }
            }
        }
    }
    fun render() {
        val results = CaptureState.journal.snapshots().map { ParserRegistry.analyze(it, enabled) }
        activity.findViewById<TextView>(R.id.parser_status).text = if (!ParserRegistry.available) {
            activity.getString(R.string.parser_unavailable)
        } else activity.getString(R.string.parser_counts,
            results.count { it is ParseResult.Candidate }, results.count { it is ParseResult.Ignored },
            results.count { it is ParseResult.Unsupported })
        val reasons = results.mapNotNull {
            when (it) {
                is ParseResult.Ignored -> it.reason
                is ParseResult.Unsupported -> it.reason
                is ParseResult.Candidate -> null
            }
        }.groupingBy { it }.eachCount().toSortedMap(compareBy { it.ordinal })
        activity.findViewById<TextView>(R.id.parser_reasons).text = reasons.entries.joinToString("；") {
            activity.getString(R.string.parser_reason_count, it.key.description, it.value)
        }
    }
}
