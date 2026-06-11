package dev.sumin.skeleton.idempotency

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class InMemoryIdempotencyStoreTest {
    @Test
    fun `reserve starts first request and replays completed same fingerprint`() {
        val store = InMemoryIdempotencyStore()
        val request = IdempotencyRequest(
            scopedKey = "user-1:key-1",
            rawKey = "key-1",
            fingerprint = "fingerprint-1",
            expiresAt = Instant.parse("2099-06-09T00:05:00Z"),
        )

        assertIs<IdempotencyReservation.Started>(store.reserve(request))

        store.complete(
            scopedKey = request.scopedKey,
            response = StoredIdempotencyResponse(
                status = 201,
                contentType = "application/json",
                headers = mapOf("Location" to listOf("/api/v1/examples/items/item-1")),
                body = """{"value":{"id":"item-1"}}""".toByteArray(),
            ),
        )

        val replay = assertIs<IdempotencyReservation.Replay>(store.reserve(request))
        assertEquals(201, replay.response.status)
        assertEquals("""{"value":{"id":"item-1"}}""", replay.response.body.decodeToString())
    }

    @Test
    fun `reserve rejects same key while first request is still in progress`() {
        val store = InMemoryIdempotencyStore()
        val request = IdempotencyRequest(
            scopedKey = "user-1:key-1",
            rawKey = "key-1",
            fingerprint = "fingerprint-1",
            expiresAt = Instant.parse("2099-06-09T00:05:00Z"),
        )

        store.reserve(request)

        assertIs<IdempotencyReservation.InProgress>(store.reserve(request))
    }

    @Test
    fun `reserve rejects same key with different fingerprint`() {
        val store = InMemoryIdempotencyStore()
        val first = IdempotencyRequest(
            scopedKey = "user-1:key-1",
            rawKey = "key-1",
            fingerprint = "fingerprint-1",
            expiresAt = Instant.parse("2099-06-09T00:05:00Z"),
        )
        val second = first.copy(fingerprint = "fingerprint-2")

        store.reserve(first)

        assertIs<IdempotencyReservation.Conflict>(store.reserve(second))
    }
}
