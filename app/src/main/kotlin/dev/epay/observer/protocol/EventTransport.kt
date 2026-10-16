package dev.epay.observer.protocol

import dev.epay.observer.storage.ReceiptAck

fun interface EventClient { fun send(origin: String, request: SignedRequest): HttpResult }
class EventTransport : EventClient {
    override fun send(origin: String, request: SignedRequest) = JsonHttpsTransport().post(origin, request.path, request.headers(), request.bodyBytes())
}

object EventResponse {
    fun ack(response: HttpResult, expectedEvent: String): ReceiptAck? = try {
        if (!response.json) null else {
            val value = StrictObject.parse(response.body)
            value.requireFields("schema_version", "event_id", "receipt_status", "processing_status", "received_at_ms")
            value.number("schema_version", 1, 1)
            val id = value.text("event_id"); val status = value.text("receipt_status"); val processing = value.text("processing_status")
            require(PairingProtocol.uuid.matches(id) && id == expectedEvent)
            require(response.status == 202 && status == "accepted" || response.status == 200 && status == "duplicate")
            require(processing in setOf("pending", "needs_review", "credited"))
            ReceiptAck(1, id, response.status, status, processing, value.number("received_at_ms"))
        }
    } catch (_: Exception) { null }

    fun error(response: HttpResult): String? = try {
        if (!response.json) null else {
            val value = StrictObject.parse(response.body); value.requireFields("schema_version", "code"); value.number("schema_version", 1, 1)
            val allowed = when (response.status) {
                400 -> setOf("invalid_payload", "unsupported_version", "invalid_request", "invalid_route")
                401 -> setOf("invalid_signature", "clock_skew", "unknown_key")
                403 -> setOf("revoked", "scope_denied", "binding_changed", "environment_denied")
                409 -> setOf("event_conflict", "request_conflict")
                413 -> setOf("body_too_large")
                415 -> setOf("unsupported_media_type")
                429 -> setOf("rate_limited")
                503 -> setOf("unavailable")
                else -> emptySet()
            }
            value.text("code").takeIf { it in allowed }
        }
    } catch (_: Exception) { null }
}
