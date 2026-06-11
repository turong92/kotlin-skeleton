package dev.sumin.skeleton.idempotency

import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

class InMemoryIdempotencyStore(
    private val clock: Clock = Clock.systemUTC(),
) : IdempotencyStore {
    private val entries = ConcurrentHashMap<String, Entry>()

    @Synchronized
    override fun reserve(request: IdempotencyRequest): IdempotencyReservation {
        purgeExpiredEntries()

        val current = entries[request.scopedKey]
        if (current == null) {
            entries[request.scopedKey] = Entry.InProgress(
                fingerprint = request.fingerprint,
                expiresAt = request.expiresAt,
            )
            return IdempotencyReservation.Started(request)
        }

        if (current.fingerprint != request.fingerprint) {
            return IdempotencyReservation.Conflict
        }

        return when (current) {
            is Entry.Completed -> IdempotencyReservation.Replay(current.response)
            is Entry.InProgress -> IdempotencyReservation.InProgress
        }
    }

    @Synchronized
    override fun complete(
        scopedKey: String,
        response: StoredIdempotencyResponse,
    ) {
        val current = entries[scopedKey] ?: return
        entries[scopedKey] = Entry.Completed(
            fingerprint = current.fingerprint,
            expiresAt = current.expiresAt,
            response = response,
        )
    }

    private fun purgeExpiredEntries() {
        val now = clock.instant()
        entries.entries.removeIf { (_, entry) -> !entry.expiresAt.isAfter(now) }
    }

    private sealed class Entry {
        abstract val fingerprint: String
        abstract val expiresAt: java.time.Instant

        data class InProgress(
            override val fingerprint: String,
            override val expiresAt: java.time.Instant,
        ) : Entry()

        data class Completed(
            override val fingerprint: String,
            override val expiresAt: java.time.Instant,
            val response: StoredIdempotencyResponse,
        ) : Entry()
    }
}
