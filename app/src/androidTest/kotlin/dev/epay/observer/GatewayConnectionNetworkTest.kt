package dev.epay.observer

import android.app.NotificationManager
import android.content.ComponentName
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.epay.observer.protocol.JsonHttpsTransport
import dev.epay.observer.protocol.PairingProtocol
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit anonymous entrance check; absent from every application APK. */
@RunWith(AndroidJUnit4::class)
class GatewayConnectionNetworkTest {
    @Test fun anonymousInspectReachesAuthenticationBoundary() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("observerConnectionProbe") == "true")
        val context = instrumentation.targetContext
        val report = JSONObject().put("schema_version", 1).put("passed", false)
            .put("http_status", 0).put("json", false).put("code", "not_attempted")
            .put("anonymous", true).put("notification_access", false).put("payment_verified", false)
        var passed = false
        try {
            check(context.packageName == "dev.epay.observer.debug")
            val manager = context.getSystemService(NotificationManager::class.java)
            val access = manager.isNotificationListenerAccessGranted(
                ComponentName(context.packageName, "dev.epay.observer.capture.ObserverListener"))
            report.put("notification_access", access)
            check(!access)
            val origin = checkNotNull(args.getString("observerOrigin"))
            val result = JsonHttpsTransport().post(origin, "/api/observer/v1/pairing/inspect",
                mapOf("Content-Type" to "application/json"), PairingProtocol.inspectBody())
            report.put("http_status", result.status).put("json", result.json)
            val code = if (result.json) {
                try { PairingProtocol.error(result.body) } catch (_: Exception) { "invalid_protocol" }
            } else "non_json"
            // Never expose an unvalidated response value in instrumentation output.
            report.put("code", if (code == "invalid_grant") code else "unexpected_response")
            passed = result.status == 401 && result.json && code == "invalid_grant"
        } catch (failure: Exception) {
            report.put("code", when (failure) {
                is SSLException -> "tls_failure"
                is SocketTimeoutException -> "timeout"
                is UnknownHostException -> "dns_failure"
                is ConnectException -> "connection_failure"
                is IllegalArgumentException, is IllegalStateException -> "precondition_failure"
                else -> "transport_failure"
            })
        } finally {
            report.put("passed", passed)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("gateway_connection_result", report.toString())
            })
        }
        assertTrue("Anonymous HTTPS authentication entrance check failed; see redacted result", passed)
    }
}
