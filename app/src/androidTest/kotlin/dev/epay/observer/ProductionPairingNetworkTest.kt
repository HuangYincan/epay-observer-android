package dev.epay.observer

import android.app.NotificationManager
import android.content.ComponentName
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.epay.observer.parsing.Provider
import dev.epay.observer.protocol.*
import dev.epay.observer.security.*
import dev.epay.observer.storage.*
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in provisioning, deliberately persistent in the application's normal stores. */
@RunWith(AndroidJUnit4::class)
class ProductionPairingNetworkTest {
    @Test fun provisionOrVerifySpecifiedReceiver() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("observerProductionPairing") == "true")
        val phase = args.getString("observerPairingPhase")
        val context = instrumentation.targetContext
        val report = JSONObject().put("schema_version", 1).put("phase", "invalid")
            .put("passed", false).put("active_credentials", 0).put("authorized", false)
            .put("nonexportable_key", false).put("empty_local_records", false)
            .put("notification_access", false).put("payment_verified", false)
        var passed = false
        try {
            check(context.packageName == "dev.epay.observer.debug" && phase in setOf("pair", "verify"))
            report.put("phase", phase)
            val access = context.getSystemService(NotificationManager::class.java)
                .isNotificationListenerAccessGranted(ComponentName(context.packageName,
                    "dev.epay.observer.capture.ObserverListener"))
            report.put("notification_access", access); check(!access)
            PairingRuntime.awaitIdle(); instrumentation.waitForIdleSync()
            val file = File(context.noBackupFilesDir, "production-pairing-input.json")
            check(file.length() in 1..8192)
            val bytes = file.readBytes()
            val input = try {
                check(file.delete())
                JSONObject(String(bytes, Charsets.UTF_8))
            } finally { bytes.fill(0) }
            check(input.keys().asSequence().toSet() == if (phase == "pair") setOf("scope", "token") else setOf("scope"))
            val scopeJson = input.getJSONObject("scope")
            val expected = PairingProtocol.scope(scopeJson.toString().toByteArray(), scopeJson.getString("origin"))
            check(expected.provider == Provider.ALIPAY && expected.sourceClass == BindingSnapshot.SourceClass.NOTIFICATION &&
                expected.mode == "replace" && expected.expectedVersion == 0 && expected.deviceId.isEmpty())
            val db = ObserverDatabase.open(context)
            try {
                fun emptyRecords(): Boolean = db.observerDao().observationCount() == 0 &&
                    db.observerDao().eventCount() == 0 && db.openHelper.readableDatabase.query("SELECT count(*) FROM outbox").use {
                        it.moveToFirst() && it.getInt(0) == 0
                    }
                check(emptyRecords())
                val coordinator = PairingCoordinator(context, db)
                if (phase == "pair") {
                    check(coordinator.active().isEmpty() && PairingJournal(context).read() == null)
                    val inspected = coordinator.inspect(expected.origin, input.getString("token"))
                    check(inspected.scope == expected)
                    coordinator.confirm(inspected)
                    PairingRuntime.recover(); PairingRuntime.awaitIdle(); instrumentation.waitForIdleSync()
                }
                val active = coordinator.active()
                report.put("active_credentials", active.size)
                check(active.keys == setOf(Provider.ALIPAY))
                val credential = active.getValue(Provider.ALIPAY)
                val binding = credential.binding
                check(binding.origin == expected.origin && binding.environment == expected.environment &&
                    binding.receiverId == expected.receiverId && binding.provider == expected.provider &&
                    binding.sourceClass == expected.sourceClass && binding.version == 1)
                check(PairingRuntime.active[Provider.ALIPAY] == credential)
                val key = checkNotNull(HmacKeyVault(context).load(binding, credential.keyId))
                val nonexportable = key.encoded == null
                report.put("nonexportable_key", nonexportable); check(nonexportable)
                val authorized = HealthCheck(context, db).check(Provider.ALIPAY) == HealthState.AUTHORIZED
                report.put("authorized", authorized); check(authorized)
                val empty = emptyRecords()
                report.put("empty_local_records", empty); check(empty)
                passed = true
            } finally { db.close() }
        } catch (_: Exception) {
            // Pending journal and committed credentials deliberately remain for normal recovery.
        } finally {
            report.put("passed", passed)
            instrumentation.sendStatus(0, Bundle().apply { putString("production_pairing_result", report.toString()) })
        }
        assertTrue("Persistent pairing/health failed; inspect redacted result and server state", passed)
    }
}
