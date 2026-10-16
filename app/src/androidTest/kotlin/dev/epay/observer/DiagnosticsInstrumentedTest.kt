package dev.epay.observer

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.epay.observer.parsing.Provider
import dev.epay.observer.status.*
import dev.epay.observer.storage.*
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticsInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun await(description: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 10000
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(50)
        assertTrue(description, condition())
    }
    @Test fun readerUsesAggregateProjectionAndPreservesUnboundHistory() {
        val name = "diagnostic-${UUID.randomUUID()}.db"
        val db = ObserverDatabase.open(context, name)
        try {
            val input = identityInput("private-notification-key")
            val event = IdentityStore(db).record(input, Provider.entries.toSet()).eventId!!
            db.observerDao().putMetadata(MetadataRow("unused-private-key-id", "private-credential-secret"))
            val before = db.observerDao().event(event)!!.payload.copyOf()
            val report = DiagnosticReport.encode(DiagnosticReader(context, db).snapshot(true, false))
            assertFalse(report.contains(event)); assertFalse(report.contains("private-"))
            assertFalse(report.contains("amount")); assertFalse(report.contains("receiver"))
            assertEquals(1, JSONObject(report).getJSONObject("storage").getInt("events"))
            assertEquals("UNBOUND", db.observerDao().recentEvents().single().localState)
            assertEquals(0, db.outboxDao().count())
            assertArrayEquals(before, db.observerDao().event(event)!!.payload)
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun previewCancellationAndSelectedDocumentKeepExactBytes() {
        val service = instrumentation.uiAutomation.serviceInfo
        service.flags = service.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        instrumentation.uiAutomation.serviceInfo = service
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "observer-test-${UUID.randomUUID()}.json")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        })!!
        // The test owns this empty pending document; cancellation must leave it empty.
        resolver.openOutputStream(uri, "w")!!.close()
        var monitor: android.app.Instrumentation.ActivityMonitor? = null
        fun previewNode() = instrumentation.uiAutomation.rootInActiveWindow
            ?.findAccessibilityNodeInfosByViewId(context.packageName + ":id/diagnostic_preview")?.firstOrNull()
        fun clickText(text: String) {
            val node = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)
                ?.firstOrNull { it.isClickable } ?: error("missing UI action")
            assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        try {
            instrumentation.runOnMainSync { activity.findViewById<Button>(R.id.preview_diagnostics).performClick() }
            await("diagnostic preview shown") { previewNode() != null }
            clickText(context.getString(android.R.string.cancel))
            await("preview cancelled") { previewNode() == null }
            assertEquals(0, resolver.openInputStream(uri)!!.use { it.readBytes().size })
            monitor = instrumentation.addMonitor(IntentFilter(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); addDataType("application/json")
            },
                ActivityResult(Activity.RESULT_OK, Intent().setData(uri)), true)
            instrumentation.runOnMainSync { activity.findViewById<Button>(R.id.preview_diagnostics).performClick() }
            await("second preview shown") { previewNode() != null }
            val expected = previewNode()!!.text.toString()
            assertEquals("aggregate-only", JSONObject(expected).getString("scope"))
            clickText("选择文件保存")
            await("selected document saved") {
                var saved = false
                instrumentation.runOnMainSync { saved = activity.findViewById<TextView>(R.id.diagnostic_status)
                    .text.toString() == context.getString(R.string.diagnostic_exported) }
                saved
            }
            assertEquals(1, monitor!!.hits)
            assertArrayEquals(expected.toByteArray(Charsets.UTF_8), resolver.openInputStream(uri)!!.use { it.readBytes() })
        } finally {
            monitor?.let(instrumentation::removeMonitor)
            resolver.delete(uri, null, null)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
