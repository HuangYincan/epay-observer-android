package dev.epay.observer

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.security.NetworkSecurityPolicy
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.URI
import javax.net.ssl.HttpsURLConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FoundationInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun isolatedClientLaunchesWithHonestInitialState() {
        assertEquals("dev.epay.observer.fixtureclient.debug", context.packageName)
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        try {
            instrumentation.runOnMainSync {
                assertEquals("隔离测试环境", activity.findViewById<TextView>(R.id.environment).text.toString())
                val connected = dev.epay.observer.capture.CaptureState.connected
                assertEquals(if (connected) "已连接" else "未连接", activity.findViewById<TextView>(R.id.listener_status).text.toString())
                assertEquals("未绑定", activity.findViewById<TextView>(R.id.binding_status).text.toString())
                assertEquals("暂无事件", activity.findViewById<TextView>(R.id.events_status).text.toString())
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun manifestDisablesBackupAndCleartext() {
        assertFalse(context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0)
        assertFalse(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted)
        for (host in listOf("localhost", "ip6-localhost", "127.0.0.1", "127.0.0.2", "::1", "[::1]", "10.0.2.2", "example.com")) {
            assertFalse("Cleartext must be denied for $host", NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host))
        }
    }

    @Test fun publicHttpsWorksWithDefaultSystemTrust() {
        // Public connectivity probe only, not a gateway or payment endpoint.
        val connection = URI("https://example.com/").toURL().openConnection() as HttpsURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.instanceFollowRedirects = false
        try {
            assertEquals(200, connection.responseCode)
            assertTrue(connection.serverCertificates.isNotEmpty())
            connection.inputStream.use { assertTrue(it.read() >= 0) }
        } finally {
            connection.disconnect()
        }
    }
}
