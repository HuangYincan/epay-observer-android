package dev.epay.observer

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import android.widget.CheckBox
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.epay.observer.capture.*
import dev.epay.observer.parsing.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ParserInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val component = ComponentName(context, ObserverListener::class.java)
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        java.io.FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText() }
    }
    private fun await(description: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 12_000
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100)
        assertTrue(description, condition())
    }
    private fun post(scenario: String) {
        assertTrue(shell("am start -W -f 0x20000000 -n dev.epay.observer.fixture/.FixtureActivity --es scenario $scenario").contains("Status: ok"))
    }
    private fun record(id: Int) = CaptureState.journal.snapshots().firstOrNull { it.latest.key.split('|').getOrNull(2) == id.toString() }
    private fun result(id: Int) = ParserRegistry.analyze(checkNotNull(record(id)), ParserPreferences(context).enabled())
    private fun launch(): Activity = instrumentation.startActivitySync(
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    private fun connection(allowed: Boolean) {
        shell("cmd notification ${if (allowed) "allow_listener" else "disallow_listener"} ${component.flattenToString()}")
        await("listener connection=$allowed") { CaptureState.connected == allowed }
    }

    @Test fun actualNotificationsParseConservativelyAndProviderControlsPersist() {
        val preferences = ParserPreferences(context)
        val original = preferences.enabled()
        var activity: Activity? = null
        try {
            Provider.entries.forEach { preferences.setEnabled(it, true) }
            shell("pm grant dev.epay.observer.fixture android.permission.POST_NOTIFICATIONS")
            connection(false); post("clear"); connection(true)
            post("parse-wx"); post("parse-ali")
            await("both providers observed") { record(610) != null && record(611) != null }
            assertEquals(ParseResult.Candidate(Provider.WXPAY, 1000, "CNY", "fixture-wx-v1"), result(610))
            assertEquals(ParseResult.Candidate(Provider.ALIPAY, 1000, "CNY", "fixture-ali-v1"), result(611))
            assertTrue((result(610) as ParseResult.Candidate).identityUncertain)
            activity = launch()
            instrumentation.runOnMainSync { activity!!.findViewById<CheckBox>(R.id.wx_parser).performClick() }
            assertEquals(ParseResult.Ignored(ParseReason.DISABLED), result(610))
            assertTrue(result(611) is ParseResult.Candidate)
            instrumentation.runOnMainSync { activity!!.finish() }; activity = launch()
            instrumentation.runOnMainSync {
                assertFalse(activity!!.findViewById<CheckBox>(R.id.wx_parser).isChecked)
                assertTrue(activity!!.findViewById<CheckBox>(R.id.ali_parser).isChecked)
                activity!!.findViewById<CheckBox>(R.id.wx_parser).performClick()
                activity!!.findViewById<CheckBox>(R.id.ali_parser).performClick()
            }
            assertTrue(result(610) is ParseResult.Candidate)
            assertEquals(ParseResult.Ignored(ParseReason.DISABLED), result(611))
            instrumentation.runOnMainSync { activity!!.findViewById<CheckBox>(R.id.ali_parser).performClick(); activity!!.finish() }
            activity = null
            val negatives = listOf(
                Triple("wx-conflict", 620, ParseResult.Unsupported(ParseReason.CONFLICT)),
                Triple("ali-conflict", 621, ParseResult.Unsupported(ParseReason.CONFLICT)),
                Triple("wx-currency", 622, ParseResult.Unsupported(ParseReason.CURRENCY)),
                Triple("ali-currency", 623, ParseResult.Unsupported(ParseReason.CURRENCY)),
                Triple("wx-refund", 624, ParseResult.Ignored(ParseReason.REFUND)),
                Triple("ali-refund", 625, ParseResult.Ignored(ParseReason.REFUND)),
                Triple("wx-chat", 626, ParseResult.Ignored(ParseReason.CHAT)),
                Triple("ali-chat", 627, ParseResult.Ignored(ParseReason.CHAT)),
                Triple("wx-promotion", 628, ParseResult.Ignored(ParseReason.PROMOTION)),
                Triple("ali-promotion", 629, ParseResult.Ignored(ParseReason.PROMOTION)),
                Triple("wx-inbox", 630, ParseResult.Unsupported(ParseReason.ADDITIONAL_TEXT)),
                Triple("ali-inbox", 631, ParseResult.Unsupported(ParseReason.ADDITIONAL_TEXT)),
                Triple("wx-precision", 632, ParseResult.Unsupported(ParseReason.AMOUNT)),
                Triple("ali-precision", 633, ParseResult.Unsupported(ParseReason.AMOUNT)),
                Triple("wx-subtext", 634, ParseResult.Unsupported(ParseReason.ADDITIONAL_TEXT)),
                Triple("ali-subtext", 635, ParseResult.Unsupported(ParseReason.ADDITIONAL_TEXT)),
            )
            for ((scenario, id, expected) in negatives) {
                post(scenario); await(scenario) { record(id) != null }
                assertEquals(scenario, expected, result(id))
            }
            post("parse-wx-update"); await("revision") { record(610)!!.revision > 1 }
            assertEquals(ParseResult.Unsupported(ParseReason.REVISION), result(610))
            connection(false); connection(true)
            await("restoration") { record(611)!!.everRestored }
            assertEquals(ParseResult.Ignored(ParseReason.RESTORED), result(611))
        } finally {
            activity?.let { a -> instrumentation.runOnMainSync { a.finish() } }
            Provider.entries.forEach { preferences.setEnabled(it, it in original) }
            post("clear"); connection(false)
        }
    }
}
