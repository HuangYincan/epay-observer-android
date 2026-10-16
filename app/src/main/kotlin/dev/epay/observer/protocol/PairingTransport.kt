package dev.epay.observer.protocol

class PairingFailure(val code: String) : IllegalStateException(code)

/** Platform certificate/hostname verification, bounded responses, no redirects.
 * Test trust configuration belongs exclusively to the fixture source set. */
fun interface PairingClient {
    fun post(origin: String, token: String, operation: String, body: ByteArray): ByteArray
}
class PairingTransport : PairingClient {
    override fun post(origin: String, token: String, operation: String, body: ByteArray): ByteArray {
        require(PairingProtocol.validSecret(token) && operation in setOf("inspect", "redeem", "activate"))
        PairingProtocol.validateRequest(body, operation)
        val response = JsonHttpsTransport().post(origin, "/api/observer/v1/pairing/$operation",
            mapOf("Authorization" to "ObserverPairing $token", "Content-Type" to "application/json"), body)
        if (response.status in 300..399) throw PairingFailure("redirect_rejected")
        if (!response.json) throw PairingFailure("invalid_response")
        if (response.status != 200) throw PairingFailure(try { PairingProtocol.error(response.body) } catch (_: Exception) { "invalid_response" })
        return response.body
    }
}
