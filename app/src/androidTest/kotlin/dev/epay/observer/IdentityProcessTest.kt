package dev.epay.observer

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.epay.observer.parsing.Provider
import dev.epay.observer.storage.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run by smoke_avd.py in two separate instrumentation processes, never in the ordinary suite. */
@RunWith(AndroidJUnit4::class)
class IdentityProcessTest {
    @Test fun persistsAcrossActualProcessDeath() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val phase = InstrumentationRegistry.getArguments().getString("identityPhase")
        require(phase == "prepare" || phase == "verify")
        val vault = dev.epay.observer.security.HmacKeyVault(context)
        val vaultKeyId = "test-process-vault"
        val name = "test-identity-process.db"
        val reference = java.io.File(context.filesDir, "test-identity-process.json")
        if (phase == "prepare") { context.deleteDatabase(name); reference.delete(); vault.remove(vaultBinding(), vaultKeyId) }
        val db = ObserverDatabase.open(context, name)
        try {
            val store = IdentityStore(db)
            val queue = OutboxStore(db)
            if (phase == "prepare") {
                vault.install(vaultBinding(), vaultKeyId, vaultMaterial())
                assertEquals(vaultGolden().getString("signature_hex"), vaultSign(vault, vaultBinding(), vaultKeyId))
                Provider.entries.forEach { queue.activate(syntheticBinding(it)) }
                repeat(50) { i -> store.record(queueInput(i), Provider.entries.toSet(), syntheticBinding(if (i % 2 == 0) Provider.WXPAY else Provider.ALIPAY)) }
                queue.claim(syntheticBinding(), 1000, 100)!! // Simulated in-flight death, real process exit below.
                val frozenQueue = org.json.JSONArray()
                queue.rows().forEach { frozenQueue.put(JSONObject().put("id", it.eventId)
                    .put("body", android.util.Base64.encodeToString(it.body, android.util.Base64.NO_WRAP))) }
                val row = store.record(identityInput("process-event"), Provider.entries.toSet())
                val event = store.event(row.eventId!!)!!
                reference.writeText(JSONObject().put("epoch", store.epoch()).put("eventId", event.eventId)
                    .put("payload", android.util.Base64.encodeToString(event.payload, android.util.Base64.NO_WRAP))
                    .put("queue", frozenQueue).put("pid", Process.myPid()).toString())
            } else {
                val expected = JSONObject(reference.readText())
                assertNotEquals(expected.getInt("pid"), Process.myPid())
                assertEquals(vaultGolden().getString("signature_hex"), vaultSign(vault, vaultBinding(), vaultKeyId))
                assertEquals(expected.getString("epoch"), store.epoch())
                val row = store.record(identityInput("process-event", restored = true).copy(wallTime = 9999), Provider.entries.toSet())
                assertEquals(expected.getString("eventId"), row.eventId)
                assertArrayEquals(android.util.Base64.decode(expected.getString("payload"), android.util.Base64.NO_WRAP), store.event(row.eventId!!)!!.payload)
                assertEquals(1000, store.event(row.eventId)!!.observedAt)
                assertEquals(51, store.stats().events)
                val frozen = expected.getJSONArray("queue")
                val restored = queue.rows().associateBy { it.eventId }
                assertEquals(50, restored.size)
                repeat(frozen.length()) { i ->
                    val saved = frozen.getJSONObject(i)
                    assertArrayEquals(android.util.Base64.decode(saved.getString("body"), android.util.Base64.NO_WRAP), restored[saved.getString("id")]!!.body)
                }
                val inFlight = restored.values.single { it.state == "IN_FLIGHT" }
                val recovered = queue.claim(syntheticBinding(), 1100)!!
                // Other READY rows may sort earlier; drain them until the expired lease is claimed.
                val claimed = mutableListOf(recovered)
                while (claimed.none { it.eventId == inFlight.eventId }) claimed.add(queue.claim(syntheticBinding(), 1100)!!)
                val lease = claimed.single { it.eventId == inFlight.eventId }
                assertNotEquals(inFlight.leaseToken, lease.token)
                assertArrayEquals(inFlight.body, lease.body)
                assertEquals(2, lease.attempt)
                assertTrue(store.events().all { it.observedAt == 1000L })
            }
        } finally { db.close(); if (phase == "verify") vault.remove(vaultBinding(), vaultKeyId) }
        if (phase == "verify") { context.deleteDatabase(name); reference.delete() }
    }
}
