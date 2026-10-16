package dev.epay.observer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.epay.observer.parsing.Provider
import dev.epay.observer.protocol.*
import dev.epay.observer.security.*
import dev.epay.observer.storage.*
import java.io.File
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

internal fun pairingScopeBytes() = JSONObject().put("schema_version", 1).put("grant_id", "test-grant")
    .put("origin", "https://observer.example").put("environment", "isolated-test").put("receiver_id", "receiver-test")
    .put("provider", "wxpay").put("source_class", "synthetic").put("mode", "replace")
    .put("expected_binding_version", 0).put("device_id", "").put("expires_at_ms", System.currentTimeMillis() + 300_000)
    .toString().toByteArray()
internal fun pairingPending() = PendingPairing("https://observer.example",
    Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 3 }), pairingScopeBytes(), PairingProtocol.newRequest())
internal class PairingTestServer(private val keyId: String = "test-pairing-key") : PairingClient {
    var calls = 0
    var dropActivation = false
    private val start = System.currentTimeMillis() - 1000
    fun response(request: EnrollmentRequest): ByteArray = JSONObject().put("schema_version", 1)
        .put("enrollment_id", request.enrollmentId).put("origin", "https://observer.example").put("environment", "isolated-test")
        .put("receiver_id", "receiver-test").put("provider", "wxpay").put("source_class", "synthetic")
        .put("device_id", "device-test").put("binding_version", 1).put("key_id", keyId)
        .put("key_base64url", Base64.getUrlEncoder().withoutPadding().encodeToString(vaultMaterial()))
        .put("not_before_ms", start).put("expires_at_ms", start + 86_400_000).toString().toByteArray()
    override fun post(origin: String, token: String, operation: String, body: ByteArray): ByteArray {
        calls++
        PairingProtocol.validateRequest(body, operation)
        if (operation == "inspect") return pairingScopeBytes()
        val json = JSONObject(String(body))
        val req = EnrollmentRequest(json.getString("enrollment_id"), json.getString("resume_secret"))
        if (operation == "redeem") return response(req)
        if (dropActivation) { dropActivation = false; throw java.io.IOException("synthetic response loss") }
        return JSONObject().put("schema_version", 1).put("enrollment_id", req.enrollmentId)
            .put("key_id", keyId).put("state", "active").toString().toByteArray()
    }
}
internal fun cleanupPairingTest(namespace: String) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    PairingJournal(context, namespace).clear()
    KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("observer.pending.v1.$namespace") }
    File(context.noBackupFilesDir, "observer-$namespace.workflow.lock").delete()
}

@RunWith(AndroidJUnit4::class)
class PairingInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun journalEncryptsAuthenticatesAndDoesNotRecreateMissingKey() {
        val namespace = "test-" + UUID.randomUUID().toString().filter(Char::isLetter)
        val pending = pairingPending(); val journal = PairingJournal(context, namespace)
        try {
            journal.save(pending)
            val file = File(context.noBackupFilesDir, "observer-$namespace.pending")
            val encrypted = file.readBytes()
            assertFalse(String(encrypted, Charsets.ISO_8859_1).contains(pending.token))
            assertFalse(String(encrypted, Charsets.ISO_8859_1).contains(pending.request.resumeSecret))
            val recovered = PairingJournal(context, namespace).read()!!
            assertEquals(pending.request.enrollmentId, recovered.request.enrollmentId)
            assertEquals(pending.request.resumeSecret, recovered.request.resumeSecret)
            assertTrue(runCatching { journal.save(pairingPending()) }.isFailure)
            file.writeBytes(encrypted.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
            assertTrue(runCatching { journal.read() }.isFailure)
            file.writeBytes(encrypted)
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            store.deleteEntry("observer.pending.v1.$namespace")
            assertTrue(runCatching { journal.read() }.isFailure)
            assertFalse(store.containsAlias("observer.pending.v1.$namespace"))
        } finally { cleanupPairingTest(namespace) }
    }
    @Test fun pendingKeyAndRoomBoundariesRecoverWithoutBindingHistoricalEvents() {
        for (stage in 0..3) {
            val namespace = "test-" + UUID.randomUUID().toString().filter(Char::isLetter)
            val name = "$namespace.db"; val db = ObserverDatabase.open(context, name)
            val pending = pairingPending(); val server = PairingTestServer("key-$namespace")
            val enrollment = PairingProtocol.enrollment(server.response(pending.request), pending.scope, pending.request)
            try {
                val identities = IdentityStore(db)
                val unbound = identities.record(identityInput("pre-pair"), Provider.entries.toSet())
                PairingJournal(context, namespace).save(pending)
                if (stage >= 1) HmacKeyVault(context).install(enrollment.binding, enrollment.keyId, enrollment.material)
                if (stage == 2) server.dropActivation = true
                if (stage == 3) PairingStore(db).activate(enrollment)
                val coordinator = PairingCoordinator(context, db, server, namespace)
                if (stage == 2) {
                    assertTrue(runCatching { coordinator.recover() }.isFailure)
                    assertNotNull(PairingJournal(context, namespace).read())
                    assertNull(PairingStore(db).active(Provider.WXPAY))
                }
                val active = coordinator.recover()!!
                assertEquals(pending.request.enrollmentId, active.enrollmentId)
                assertEquals(enrollment.binding, active.binding)
                assertNull(PairingJournal(context, namespace).read())
                if (stage == 3) assertEquals(0, server.calls)
                assertEquals("UNBOUND", identities.event(unbound.eventId!!)!!.state)
                assertTrue(OutboxStore(db).rows().isEmpty())
                identities.record(identityInput("pre-pair"), Provider.entries.toSet(), active.binding)
                assertTrue(OutboxStore(db).rows().isEmpty())
                identities.record(identityInput("after-pair"), Provider.entries.toSet(), active.binding)
                assertEquals(1, OutboxStore(db).rows().size)
                HmacKeyVault(context).remove(active.binding, active.keyId)
                assertTrue(coordinator.active().isEmpty())
                assertEquals("HELD", OutboxStore(db).rows().single().state)
            } finally {
                enrollment.material.fill(0)
                HmacKeyVault(context).remove(enrollment.binding, enrollment.keyId)
                cleanupPairingTest(namespace); db.close(); context.deleteDatabase(name)
            }
        }
    }
    @Test fun rotationResumesOnlyPairingHeldBytesAndKeepsConflictsHeld() {
        val name = "pairing-rotation-${UUID.randomUUID()}.db"; val db = ObserverDatabase.open(context, name)
        val pending = pairingPending(); val server = PairingTestServer()
        val first = PairingProtocol.enrollment(server.response(pending.request), pending.scope, pending.request)
        try {
            val store = PairingStore(db); val identities = IdentityStore(db)
            store.activate(first)
            identities.record(identityInput("normal"), Provider.entries.toSet(), first.binding)
            identities.record(identityInput("conflict"), Provider.entries.toSet(), first.binding)
            identities.record(identityInput("conflict").copy(postTime = 42), Provider.entries.toSet(), first.binding)
            val before = OutboxStore(db).rows().associate { it.eventId to it.body }
            store.pause(Provider.WXPAY)
            val next = PairingEnrollment(UUID.randomUUID().toString(), first.binding, "rotated", vaultMaterial(), first.notBefore, first.expiresAt)
            store.activate(next)
            val rows = OutboxStore(db).rows()
            assertEquals(1, rows.count { it.state == "READY" })
            assertEquals(1, rows.count { it.state == "HELD" && it.reason == "IDENTITY_CONFLICT" })
            rows.forEach { assertArrayEquals(before[it.eventId], it.body) }
            assertEquals("rotated", store.active(Provider.WXPAY)!!.keyId)
        } finally { first.material.fill(0); db.close(); context.deleteDatabase(name) }
    }
}

@RunWith(AndroidJUnit4::class)
class PairingProcessTest {
    @Test fun encryptedPendingSurvivesActualProcessRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val journal = PairingJournal(context, "pairing-process-test")
        when (InstrumentationRegistry.getArguments().getString("pairingPhase")) {
            "prepare" -> { assertNull(journal.read()); journal.save(pairingPending()); assertNotNull(journal.read()) }
            "verify" -> try {
                val pending = journal.read()!!
                assertEquals("receiver-test", pending.scope.receiverId)
                assertTrue(PairingProtocol.validSecret(pending.request.resumeSecret))
            } finally { cleanupPairingTest("pairing-process-test") }
            else -> error("pairingPhase required")
        }
    }
}
