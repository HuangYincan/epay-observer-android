package dev.epay.observer.protocol

object HealthProtocol {
    const val PATH = "/api/observer/v1/health"
    fun authorized(response: HttpResult): Boolean = try {
        if (response.status != 200 || !response.json) false else {
            val value = StrictObject.parse(response.body)
            value.requireFields("schema_version", "status", "server_time_ms")
            value.number("schema_version", 1, 1); value.number("server_time_ms")
            value.text("status") == "authorized"
        }
    } catch (_: Exception) { false }
}
