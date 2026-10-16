package dev.epay.observer.protocol

import java.net.URL
import javax.net.ssl.HttpsURLConnection

class PairingFailure(val code: String) : IllegalStateException(code)

/** Platform certificate/hostname verification, bounded responses, no redirects.
 * Test trust configuration belongs exclusively to the fixture source set. */
fun interface PairingClient {
    fun post(origin: String, token: String, operation: String, body: ByteArray): ByteArray
}
class PairingTransport : PairingClient {
    override fun post(origin: String, token: String, operation: String, body: ByteArray): ByteArray {
        // BindingSnapshot validates origin elsewhere; validate again at this credential boundary.
        val uri = java.net.URI(origin)
        require(uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null && uri.rawPath.isEmpty() &&
            uri.rawQuery == null && uri.rawFragment == null && (uri.port == -1 || uri.port in 1..65535))
        require(PairingProtocol.validSecret(token) && operation in setOf("inspect", "redeem", "activate"))
        PairingProtocol.validateRequest(body, operation)
        val connection = URL("$origin/api/observer/v1/pairing/$operation").openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000; connection.readTimeout = 15_000
            connection.requestMethod = "POST"; connection.doOutput = true; connection.useCaches = false
            connection.setRequestProperty("Authorization", "ObserverPairing $token")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            if (status in 300..399) throw PairingFailure("redirect_rejected")
            val contentType = connection.contentType?.lowercase(java.util.Locale.ROOT)
            if (contentType != "application/json" && contentType != "application/json; charset=utf-8") throw PairingFailure("invalid_response")
            if (connection.contentEncoding != null) throw PairingFailure("invalid_response")
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.bounded(4096) } ?: throw PairingFailure("invalid_response")
            if (bytes.isEmpty() || bytes.size > 4096) throw PairingFailure("invalid_response")
            if (status != 200) {
                val code = try { PairingProtocol.error(bytes) } catch (_: Exception) { "invalid_response" }
                throw PairingFailure(code)
            }
            return bytes
        } finally { connection.disconnect() }
    }
}
