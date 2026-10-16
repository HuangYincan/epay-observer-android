package dev.epay.observer

import dev.epay.observer.protocol.*
import dev.epay.observer.security.PairingCoordinator
import java.util.Base64
import org.junit.Assert.*

internal fun verifyPairingTlsBoundary(coordinator: PairingCoordinator) {
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
}
