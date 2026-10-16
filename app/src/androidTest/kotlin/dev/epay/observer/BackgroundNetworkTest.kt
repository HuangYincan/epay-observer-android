package dev.epay.observer

import android.content.ComponentName
import android.net.ConnectivityManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.epay.observer.capture.CaptureState
import dev.epay.observer.capture.ObserverListener
import dev.epay.observer.parsing.Provider
import dev.epay.observer.security.PairingCoordinator
import dev.epay.observer.security.PairingRuntime
import dev.epay.observer.storage.DurableCapture
import dev.epay.observer.storage.ObserverDatabase
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit host-orchestrated phases only; never run in the generic suite. */
@RunWith(AndroidJUnit4::class)
class BackgroundNetworkTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        java.io.FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText() }
    }
    private fun await(description: String, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 15000
        while (!condition() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100)
        assertTrue(description, condition())
    }
    private fun post(scenario: String) {
        assertTrue(shell("am start -W -f 0x20000000 -n dev.epay.observer.fixture/.FixtureActivity --es scenario $scenario").contains("Status: ok"))
    }
    @Test fun backgroundRecoveryPhase() {
        val phase = InstrumentationRegistry.getArguments().getString("backgroundPhase") ?: error("explicit backgroundPhase required")
        val stages = listOf("process", "reboot", "force", "doze")
        val stage = phase.substringAfter('-')
        val index = stages.indexOf(stage); require(index >= 0)
        val target = (index + 1) * 2
        PairingRuntime.awaitIdle(); instrumentation.waitForIdleSync()
        val db = ObserverDatabase.open(context)
        val component = ComponentName(context, ObserverListener::class.java).flattenToString()
        try {
            if (phase.startsWith("prepare-")) {
                if (index == 0) {
                    assertEquals(0, db.observerDao().eventCount())
                    val file = File(context.noBackupFilesDir, "pairing-network-bootstrap.json")
                    val bytes = file.readBytes(); check(file.delete())
                    val grants = try { JSONArray(String(bytes)) } finally { bytes.fill(0) }
                    val coordinator = PairingCoordinator(context, db)
                    for (i in 0 until grants.length()) {
                        val grant = grants.getJSONObject(i)
                        coordinator.confirm(coordinator.inspect(grant.getJSONObject("scope").getString("origin"), grant.getString("token")))
                    }
                    PairingRuntime.recover(); PairingRuntime.awaitIdle(); instrumentation.waitForIdleSync()
                }
                assertEquals(Provider.entries.toSet(), PairingRuntime.active.keys)
                assertEquals(target - 2, db.outboxDao().rows().count { it.state == "ACKED" })
                shell("svc wifi disable"); shell("svc data disable")
                await("network fully disconnected") { context.getSystemService(ConnectivityManager::class.java).activeNetwork == null }
                shell("pm grant dev.epay.observer.fixture android.permission.POST_NOTIFICATIONS")
                post("clear")
                shell("cmd notification allow_listener $component")
                await("listener connected") { CaptureState.connected }
                post("wxpay"); post("alipay")
                await("offline notifications committed") { db.outboxDao().count() == target }
                DurableCapture.awaitIdle()
                assertEquals(target - 2, db.outboxDao().rows().count { it.state == "ACKED" })
                assertEquals(2, db.outboxDao().rows().count { it.state == "READY" && it.attempts == 0L })
                post("clear")
                shell("cmd notification disallow_listener $component")
                await("listener disconnected") { !CaptureState.connected }
            } else {
                require(phase.startsWith("verify-"))
                // The host already observed durable gateway requests before
                // launching this instrumentation process. Only await local ACK.
                await("all durable events acknowledged") { db.outboxDao().rows().count { it.state == "ACKED" } == target }
                assertEquals(target, db.observerDao().eventCount())
                assertEquals(target.toLong() + 1, db.outboxDao().rows().sumOf { it.attempts })
                val report = JSONObject().put("layer", "emulator synthetic background HTTPS")
                    .put("stage", stage).put("acked_events", target).put("requests", target + 1)
                    .put("payment_verified", false)
                File(context.filesDir, "avd-background-result.json").writeText(report.toString(2))
            }
        } finally { db.close() }
    }
}
