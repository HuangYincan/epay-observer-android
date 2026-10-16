package dev.epay.observer

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.epay.observer.capture.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val component = ComponentName(context, ObserverListener::class.java)
    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        java.io.FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText() }
    }
    private fun await(description: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 12_000
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100)
        assertTrue(description, condition())
    }
    private fun post(scenario: String) {
        val result = shell("am start -W -f 0x20000000 -n dev.epay.observer.fixture/.FixtureActivity --es scenario $scenario")
        assertTrue("fixture launch failed", result.contains("Status: ok"))
    }
    private fun record(id: Int) = CaptureState.journal.snapshots().find { it.latest.key.split('|').getOrNull(2) == id.toString() }
    private fun grant(allowed: Boolean) {
        shell("cmd notification ${if (allowed) "allow_listener" else "disallow_listener"} ${component.flattenToString()}")
        await("access $allowed") { context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component) == allowed }
        await("connection $allowed") { CaptureState.connected == allowed }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        try {
            instrumentation.runOnMainSync {
                assertEquals(if (allowed) "已授权" else "未授权", activity.findViewById<android.widget.TextView>(R.id.permission_status).text.toString())
                assertEquals(if (allowed) "已连接" else "未连接", activity.findViewById<android.widget.TextView>(R.id.listener_status).text.toString())
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun systemDeliveryFilteringAndLifecycle() {
        shell("pm grant dev.epay.observer.fixture android.permission.POST_NOTIFICATIONS")
        grant(false)
        post("clear")
        try {
            grant(true)
            post("wxpay"); await("wx fixture") { record(101) != null }
            post("alipay"); await("ali fixture") { record(102) != null }
            assertEquals("到账 10.00 元", record(102)!!.latest.fields.text)
            assertTrue(record(101)!!.latest.source.signers.isNotEmpty())
            dev.epay.observer.storage.DurableCapture.awaitIdle()
            val database = dev.epay.observer.storage.ObserverDatabase.open(context)
            try {
                val store = dev.epay.observer.storage.IdentityStore(database)
                val observed = record(101)!!.latest
                val identity = dev.epay.observer.storage.IdentityEncoding.identity(store.epoch(), observed.source.user, observed.source.packageName, observed.key)
                assertNotNull("system delivery persisted", database.observerDao().observation(identity))
            } finally { database.close() }
            val first = record(101)!!.firstWallTime
            post("identical-pair"); await("two distinct keys") { record(201) != null && record(202) != null }
            assertEquals(record(201)!!.latest.fields, record(202)!!.latest.fields)
            assertNotEquals(record(201)!!.latest.key, record(202)!!.latest.key)
            post("update"); await("same key update") { record(101)!!.latest.fields.text == "收款 20.00 元" }
            assertTrue(record(101)!!.revision >= 2)
            assertEquals(first, record(101)!!.firstWallTime)
            post("repost"); await("repost") { record(101)!!.latest.fields.text == "收款 10.00 元" }
            post("summary"); post("child")
            await("group") { record(301) != null && record(302) != null }
            assertTrue(record(301)!!.latest.fields.flags and Notification.FLAG_GROUP_SUMMARY != 0)
            for ((scenario, id) in listOf("refund" to 401, "expense" to 402, "chat" to 403)) {
                post(scenario); await(scenario) { record(id) != null }
            }
            assertEquals(Notification.CATEGORY_MESSAGE, record(403)!!.latest.fields.category)
            post("malformed"); await("malformed system notification") { record(501) != null }
            assertTrue(record(501)!!.latest.fields.unsupported)
            post("oversized"); await("bounded fields") { record(502) != null }
            assertTrue(record(502)!!.latest.fields.unsupported)
            assertNull(record(502)!!.latest.fields.text)
            val rejected = CaptureState.rejected
            shell("cmd notification post -t synthetic-unrelated observer-unrelated synthetic-text")
            await("unrelated source rejected") { CaptureState.rejected > rejected }
            assertTrue(CaptureState.journal.snapshots().all { it.latest.source.packageName == "dev.epay.observer.fixture" })
            grant(false)
            val before = record(102)!!.deliveries
            post("alipay")
            SystemClock.sleep(500)
            assertEquals(before, record(102)!!.deliveries)
            grant(true)
            await("restored active notification") { record(101)!!.everRestored && record(102)!!.deliveries > before }
            assertEquals(first, record(101)!!.firstWallTime)
            assertTrue(CaptureState.journal.snapshots().all { it.uncertain })
            post("clear"); await("notification removal") { record(101)!!.removed }
        } finally {
            post("clear")
            grant(false)
        }
    }

    @Test fun fieldExtractionRejectsUnexpectedTypesAndPreservesCharSequence() {
        val notification = Notification.Builder(context, "synthetic-test").setSmallIcon(android.R.drawable.ic_dialog_info).build()
        notification.extras.putCharSequence(Notification.EXTRA_TITLE, android.text.SpannableString("合成标题"))
        notification.extras.putInt(Notification.EXTRA_TEXT, 42)
        val fields = extractFields(notification)
        assertEquals("合成标题", fields.title)
        assertTrue(fields.unsupported)
        assertNull(fields.text)
    }
}
