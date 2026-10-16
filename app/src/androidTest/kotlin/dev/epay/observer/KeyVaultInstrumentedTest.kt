package dev.epay.observer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.epay.observer.protocol.RequestSigner
import dev.epay.observer.security.*
import dev.epay.observer.storage.BindingSnapshot
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

internal fun vaultGolden(): JSONObject = InstrumentationRegistry.getInstrumentation().context.assets.open("vectors.json").use {
    JSONObject(it.bufferedReader().readText()).getJSONArray("vectors").getJSONObject(0)
}
internal fun vaultMaterial(): ByteArray = ByteArray(32) { it.toByte() } // Public golden only.
internal fun vaultBinding() = syntheticBinding().copy(deviceId = "keystore-test-device")
internal fun vaultSign(vault: HmacKeyVault, binding: BindingSnapshot, keyId: String): String {
    val v = vaultGolden()
    val key = requireNotNull(vault.load(binding, keyId))
    assertNull("AndroidKeyStore secret must be non-exportable via getEncoded", key.encoded)
    return RequestSigner({ v.getLong("sent_at_ms") }, { v.getString("request_id") })
        .prepare(Base64.getDecoder().decode(v.getString("body_base64")), v.getString("key_id"), key)
        .headers().getValue("X-Observer-Signature")
}

@RunWith(AndroidJUnit4::class)
class KeyVaultInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val binding = vaultBinding()
    private fun withKey(block: (HmacKeyVault, String) -> Unit) {
        val vault = HmacKeyVault(context)
        val keyId = "test-" + UUID.randomUUID().toString()
        try { block(vault, keyId) } finally { vault.remove(binding, keyId) }
    }
    @Test fun nonExportablePlatformKeySignsGoldenAndWipesMaterial() = withKey { vault, keyId ->
        val bytes = vaultMaterial()
        assertEquals(KeyInstallation.CREATED, vault.install(binding, keyId, bytes))
        assertTrue(bytes.all { it == 0.toByte() })
        assertEquals(vaultGolden().getString("signature_hex"), vaultSign(vault, binding, keyId))
    }
    @Test fun repeatedImportIsIdempotentButConflictingMaterialNeverReplacesKey() = withKey { vault, keyId ->
        vault.install(binding, keyId, vaultMaterial())
        assertEquals(KeyInstallation.ALREADY_PRESENT, vault.install(binding, keyId, vaultMaterial()))
        val conflict = ByteArray(32) { 99 }
        val error = runCatching { vault.install(binding, keyId, conflict) }.exceptionOrNull() as VaultFailure
        assertEquals(VaultFailureKind.KEY_CONFLICT, error.kind)
        assertTrue(conflict.all { it == 0.toByte() })
        assertEquals(vaultGolden().getString("signature_hex"), vaultSign(vault, binding, keyId))
    }
    @Test fun scopeRotationDeletionAndInvalidInputsFailClosed() = withKey { vault, keyId ->
        vault.install(binding, keyId, vaultMaterial())
        assertNull(vault.load(binding.copy(version = 2), keyId))
        assertNull(vault.load(binding.copy(environment = "different"), keyId))
        assertNull(vault.load(binding.copy(origin = "https://different.example"), keyId))
        assertNull(vault.load(binding, "different-key-id"))
        val short = ByteArray(31) { 1 }
        assertEquals(VaultFailureKind.INVALID_INPUT, (runCatching { vault.install(binding, keyId, short) }.exceptionOrNull() as VaultFailure).kind)
        assertTrue(short.all { it == 0.toByte() })
        val invalid = vaultMaterial()
        assertEquals(VaultFailureKind.INVALID_INPUT, (runCatching { vault.install(binding, "unsafe\nkey", invalid) }.exceptionOrNull() as VaultFailure).kind)
        assertTrue(invalid.all { it == 0.toByte() })
        vault.remove(binding, keyId)
        vault.remove(binding, keyId)
        assertNull(vault.load(binding, keyId))
    }
    @Test fun concurrentVaultInstancesCannotOverwriteAnExistingAlias() = withKey { vault, keyId ->
        val executor = Executors.newFixedThreadPool(4)
        try {
            val results = (0 until 8).map { executor.submit<KeyInstallation> { HmacKeyVault(context).install(binding, keyId, vaultMaterial()) } }
                .map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it == KeyInstallation.CREATED })
            assertEquals(7, results.count { it == KeyInstallation.ALREADY_PRESENT })
            assertEquals(vaultGolden().getString("signature_hex"), vaultSign(vault, binding, keyId))
        } finally { executor.shutdownNow() }
    }
}
