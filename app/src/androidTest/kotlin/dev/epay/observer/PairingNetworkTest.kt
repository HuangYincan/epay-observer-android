package dev.epay.observer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.epay.observer.parsing.Provider
import dev.epay.observer.protocol.PairingProtocol
import dev.epay.observer.protocol.PairingTransport
import dev.epay.observer.protocol.PairingFailure
import dev.epay.observer.security.*
import dev.epay.observer.storage.*
import java.io.File
import java.util.UUID
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit local TLS integration run; bootstrap is transient private test data.
 * This class and its file input are absent from every application APK. */
@RunWith(AndroidJUnit4::class)
class PairingNetworkTest {
    @Test fun bothChannelsPairOverActualHttpsAndActivateOnlyFreshEvents() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.noBackupFilesDir, "pairing-network-bootstrap.json")
        val bootstrap = file.readBytes()
        check(file.delete())
        val grants = try { JSONArray(String(bootstrap, Charsets.UTF_8)) } finally { bootstrap.fill(0) }
        assertEquals(2, grants.length())
        val databaseName = "test-network-${UUID.randomUUID()}.db"
        val db = ObserverDatabase.open(context, databaseName)
        val coordinator = PairingCoordinator(context, db, namespace = "pairing-network-test")
        val activated = mutableListOf<ActiveCredential>()
        try {
            val transport = PairingTransport()
            val redirectToken = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32))
            val unknownCA = runCatching {
                // Only localhost has the fixture CA anchor. The certificate also
                // has a valid 127.0.0.1 SAN, so this tests trust rather than naming.
                transport.post("https://127.0.0.1:18443", redirectToken, "inspect", PairingProtocol.inspectBody())
            }.exceptionOrNull()
            assertTrue("unknown CA must fail TLS", unknownCA is javax.net.ssl.SSLHandshakeException)
            val redirected = runCatching {
                transport.post("https://localhost:18443", redirectToken, "inspect", PairingProtocol.inspectBody())
            }.exceptionOrNull()
            assertEquals("redirect_rejected", (redirected as? PairingFailure)?.code)
            val originToken = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 1 })
            assertTrue(runCatching { coordinator.inspect("https://localhost:18443", originToken) }.exceptionOrNull() is IllegalArgumentException)
            val identities = IdentityStore(db)
            val old = (0..1).map { identities.record(queueInput(it).copy(key = "before-network-$it"), Provider.entries.toSet()) }
            for (i in 0 until grants.length()) {
                val grant = grants.getJSONObject(i)
                val expected = PairingProtocol.scope(grant.getJSONObject("scope").toString().toByteArray(), "https://localhost:18443")
                val confirmed = coordinator.inspect(expected.origin, grant.getString("token"))
                assertEquals(expected, confirmed.scope)
                val active = coordinator.confirm(confirmed)
                activated += active
                assertEquals(expected.provider, active.binding.provider)
                assertEquals(expected.receiverId, active.binding.receiverId)
                assertNull(HmacKeyVault(context).load(active.binding, active.keyId)!!.encoded)
                val input = if (active.binding.provider == Provider.WXPAY) queueInput(10) else queueInput(11)
                identities.record(input, Provider.entries.toSet(), credentialsAtCapture = mapOf(active.binding.provider to active))
            }
            assertEquals(Provider.entries.toSet(), activated.map { it.binding.provider }.toSet())
            old.forEach { assertEquals("UNBOUND", identities.event(it.eventId!!)!!.state) }
            val rows = OutboxStore(db).rows()
            assertEquals(2, rows.size)
            rows.forEach { assertEquals("READY", it.state); assertFalse(String(it.body).contains("order_id")) }
            val report = JSONObject().put("layer", "emulator synthetic pairing over HTTPS")
                .put("providers", JSONArray(listOf("wxpay", "alipay"))).put("active_credentials", activated.size)
                .put("historical_unbound", old.size).put("fresh_queued", rows.size)
                .put("events_uploaded", false).put("payment_verified", false)
                .put("unknown_ca_rejected", true).put("redirect_rejected", true).put("unexpected_origin_rejected", true)
            File(context.filesDir, "avd-pairing-result.json").writeText(report.toString(2))
        } finally {
            activated.forEach { HmacKeyVault(context).remove(it.binding, it.keyId) }
            cleanupPairingTest("pairing-network-test")
            db.close(); context.deleteDatabase(databaseName)
        }
    }
}
