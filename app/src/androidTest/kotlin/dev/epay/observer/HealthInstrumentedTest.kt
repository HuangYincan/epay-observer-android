package dev.epay.observer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.epay.observer.parsing.Provider
import dev.epay.observer.protocol.*
import dev.epay.observer.security.HmacKeyVault
import dev.epay.observer.storage.*
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HealthInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun healthDoesNotResumePausedEventsAndRejectsStaleAuthority() {
        val name = "health-${UUID.randomUUID()}.db"; val db = ObserverDatabase.open(context, name)
        val binding = syntheticBinding(); val keyId = "health-${UUID.randomUUID()}"
        val vault = HmacKeyVault(context); val pairing = PairingStore(db)
        try {
            vault.install(binding, keyId, vaultMaterial())
            pairing.activate(PairingEnrollment(UUID.randomUUID().toString(), binding, keyId, byteArrayOf(), 0, 1000000))
            val active = pairing.active(Provider.WXPAY)!!
            IdentityStore(db).record(identityInput("health-private-key"), Provider.entries.toSet(),
                credentialsAtCapture = mapOf(Provider.WXPAY to active))
            pairing.pauseUploads(active, HoldReason.AUTHENTICATION)
            val before = db.outboxDao().rows().single()
            val requests = mutableListOf<SignedRequest>()
            val success = HttpResult(200, "{\"schema_version\":1,\"status\":\"authorized\",\"server_time_ms\":2000}".toByteArray(), true)
            val check = HealthCheck(context, db, EventClient { _, request -> requests += request; success }, { 2000 })
            assertEquals(HealthState.AUTHORIZED, check.check(Provider.WXPAY))
            assertEquals(HealthState.AUTHORIZED, check.check(Provider.WXPAY))
            assertEquals(HealthProtocol.PATH, requests.first().path)
            assertNotEquals(requests[0].headers()["X-Observer-Request-Id"], requests[1].headers()["X-Observer-Request-Id"])
            val after = db.outboxDao().rows().single()
            assertArrayEquals(before.body, after.body); assertEquals(before.state, after.state)
            assertEquals(before.attempts, after.attempts); assertEquals(before.nextAttemptAt, after.nextAttemptAt)
            assertEquals("AUTHENTICATION", pairing.uploadHold(Provider.WXPAY))
            val delayed = HealthCheck(context, db, EventClient { _, _ -> pairing.resumeUploads(Provider.WXPAY); success }, { 2000 })
            assertEquals(HealthState.CHANGED, delayed.check(Provider.WXPAY))
            vault.remove(binding, keyId)
            assertEquals(HealthState.LOCAL_KEY, check.check(Provider.WXPAY))
            assertEquals(2, requests.size)
        } finally { vault.remove(binding, keyId); db.close(); context.deleteDatabase(name) }
    }
}
