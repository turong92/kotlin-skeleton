package dev.sumin.skeleton.idempotency

import java.time.Instant

data class IdempotencyRequest(
    val scopedKey: String,
    val rawKey: String,
    val fingerprint: String,
    val expiresAt: Instant,
)

data class StoredIdempotencyResponse(
    val status: Int,
    val contentType: String?,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
)

sealed interface IdempotencyReservation {
    data class Started(val request: IdempotencyRequest) : IdempotencyReservation
    data class Replay(val response: StoredIdempotencyResponse) : IdempotencyReservation
    data object Conflict : IdempotencyReservation
    data object InProgress : IdempotencyReservation
}

interface IdempotencyStore {
    fun reserve(request: IdempotencyRequest): IdempotencyReservation

    fun complete(
        scopedKey: String,
        response: StoredIdempotencyResponse,
    )
}
