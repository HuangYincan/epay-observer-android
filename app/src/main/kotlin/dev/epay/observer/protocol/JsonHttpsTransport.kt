package dev.epay.observer.protocol

import java.net.URI
import java.net.URL
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

class HttpResult(val status: Int, val body: ByteArray, val json: Boolean, val retryAfter: String? = null) {
    override fun toString() = "HttpResult(status=$status, redacted)"
}

/** Shared credential boundary: platform TLS, exact origin/path, no redirect or
 * compression, bounded response. No raw request/response enters diagnostics. */
class JsonHttpsTransport {
    fun post(origin: String, path: String, headers: Map<String, String>, body: ByteArray): HttpResult {
        val uri = URI(origin)
        require(uri.scheme == "https" && uri.host != null && uri.host == uri.host.lowercase(Locale.ROOT) &&
            uri.rawUserInfo == null && uri.rawPath.isEmpty() && uri.rawQuery == null && uri.rawFragment == null &&
            (uri.port == -1 || uri.port in 1..65535))
        require(origin == "https://${uri.host}" + if (uri.port == -1) "" else ":${uri.port}")
        require(path in setOf("/api/observer/v1/events", HealthProtocol.PATH, "/api/observer/v1/pairing/inspect", "/api/observer/v1/pairing/redeem", "/api/observer/v1/pairing/activate"))
        require(body.size in 1..16384)
        val connection = URL(origin + path).openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000; connection.readTimeout = 15_000
            connection.requestMethod = "POST"; connection.doOutput = true; connection.useCaches = false
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val retryAfter = connection.headerFields.entries.filter { it.key?.equals("Retry-After", true) == true }
                .flatMap { it.value }.singleOrNull()
            if (status in 300..399) return HttpResult(status, byteArrayOf(), false, retryAfter)
            val contentType = connection.contentType?.trim()?.lowercase(Locale.ROOT) ?: ""
            val json = Regex("application/json(?:\\s*;\\s*charset=(?:utf-8|\"utf-8\"))?").matches(contentType) && connection.contentEncoding == null
            if (!json) return HttpResult(status, byteArrayOf(), false, retryAfter)
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = try { stream?.use { it.bounded(4096) } ?: byteArrayOf() }
            catch (_: IllegalArgumentException) { byteArrayOf() }
            return HttpResult(status, bytes, bytes.isNotEmpty(), retryAfter)
        } finally { connection.disconnect() }
    }
}
