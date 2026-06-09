package dev.sumin.skeleton.persistence.jpa

import dev.sumin.skeleton.common.audit.BaseAuditTimestamps
import dev.sumin.skeleton.common.time.TimeProvider
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.Embedded
import jakarta.persistence.MappedSuperclass
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AuditTimestampsTest {
    @Test
    fun `jpa audit timestamps implement the common contract and expose DATETIME columns`() {
        val timestamps = AuditTimestamps(
            createdAt = Instant.parse("2026-06-09T01:00:00Z"),
            updatedAt = Instant.parse("2026-06-09T01:01:00Z"),
            deletedAt = null,
        )

        val commonTimestamps: BaseAuditTimestamps = timestamps

        assertEquals(Instant.parse("2026-06-09T01:00:00Z"), commonTimestamps.createdAt)
        assertNotNull(AuditTimestamps::class.java.getAnnotation(Embeddable::class.java))
        assertColumn("createdAt", "created_at", nullable = false)
        assertColumn("updatedAt", "updated_at", nullable = false)
        assertColumn("deletedAt", "deleted_at", nullable = true)
    }

    @Test
    fun `jpa audit timestamps mark created and updated with one truncated instant`() {
        val provider = TimeProvider.fixed(Instant.parse("2026-06-09T01:02:03.123456789Z"))
        val timestamps = AuditTimestamps(
            createdAt = Instant.parse("2026-06-09T01:00:00Z"),
            updatedAt = Instant.parse("2026-06-09T01:01:00Z"),
        )

        timestamps.markCreated(provider)

        assertEquals(Instant.parse("2026-06-09T01:02:03.123456Z"), timestamps.createdAt)
        assertEquals(Instant.parse("2026-06-09T01:02:03.123456Z"), timestamps.updatedAt)

        timestamps.markUpdated(TimeProvider.fixed(Instant.parse("2026-06-09T02:03:04.654321987Z")))

        assertEquals(Instant.parse("2026-06-09T01:02:03.123456Z"), timestamps.createdAt)
        assertEquals(Instant.parse("2026-06-09T02:03:04.654321Z"), timestamps.updatedAt)
    }

    @Test
    fun `base jpa entity embeds audit timestamps and delegates lifecycle callbacks`() {
        val entity = SampleJpaEntity(TimeProvider.fixed(Instant.parse("2026-06-09T03:00:00.999999999Z")))

        assertNotNull(BaseJpaEntity::class.java.getAnnotation(MappedSuperclass::class.java))
        assertNotNull(BaseJpaEntity::class.java.getDeclaredField("audit").getAnnotation(Embedded::class.java))

        entity.prePersistAudit()

        assertEquals(Instant.parse("2026-06-09T03:00:00.999999Z"), entity.audit.createdAt)
        assertEquals(Instant.parse("2026-06-09T03:00:00.999999Z"), entity.audit.updatedAt)

        entity.currentTimeProvider = TimeProvider.fixed(Instant.parse("2026-06-09T04:00:00.111111999Z"))
        entity.preUpdateAudit()

        assertEquals(Instant.parse("2026-06-09T03:00:00.999999Z"), entity.audit.createdAt)
        assertEquals(Instant.parse("2026-06-09T04:00:00.111111Z"), entity.audit.updatedAt)
    }

    private fun assertColumn(fieldName: String, columnName: String, nullable: Boolean) {
        val column = AuditTimestamps::class.java.getDeclaredField(fieldName).getAnnotation(Column::class.java)

        assertNotNull(column)
        assertEquals(columnName, column.name)
        assertEquals(nullable, column.nullable)
        assertEquals("DATETIME(6)", column.columnDefinition)
    }

    private class SampleJpaEntity(
        var currentTimeProvider: TimeProvider,
    ) : BaseJpaEntity() {
        override fun timeProvider(): TimeProvider = currentTimeProvider
    }
}
