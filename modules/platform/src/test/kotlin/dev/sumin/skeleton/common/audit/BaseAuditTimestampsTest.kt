package dev.sumin.skeleton.common.audit

import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class BaseAuditTimestampsTest {
    @Test
    fun `time provider truncates instants to MySQL DATETIME microsecond precision`() {
        val raw = Instant.parse("2026-06-09T01:02:03.123456789Z")
        val provider = TimeProvider.fixed(raw)

        assertEquals(Instant.parse("2026-06-09T01:02:03.123456Z"), provider.now())
        assertEquals(Instant.parse("2026-06-09T01:02:03.123456Z"), TimeProvider.truncateToDatabasePrecision(raw))
    }

    @Test
    fun `base audit timestamps contract exposes non-null created and updated instants`() {
        val timestamps = SampleAuditTimestamps(
            createdAt = Instant.parse("2026-06-09T01:00:00Z"),
            updatedAt = Instant.parse("2026-06-09T01:01:00Z"),
            deletedAt = null,
        )

        assertEquals(Instant.parse("2026-06-09T01:00:00Z"), timestamps.createdAt)
        assertEquals(Instant.parse("2026-06-09T01:01:00Z"), timestamps.updatedAt)
        assertEquals(null, timestamps.deletedAt)
    }

    private data class SampleAuditTimestamps(
        override val createdAt: Instant,
        override val updatedAt: Instant,
        override val deletedAt: Instant?,
    ) : BaseAuditTimestamps
}
