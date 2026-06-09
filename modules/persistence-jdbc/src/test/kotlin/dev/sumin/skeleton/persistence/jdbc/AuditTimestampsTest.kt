package dev.sumin.skeleton.persistence.jdbc

import dev.sumin.skeleton.common.audit.BaseAuditTimestamps
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.springframework.data.relational.core.mapping.Column

class AuditTimestampsTest {
    @Test
    fun `jdbc audit timestamps are immutable and implement the common contract`() {
        val timestamps = AuditTimestamps.now(TimeProvider.fixed(Instant.parse("2026-06-09T01:02:03.123456789Z")))
        val commonTimestamps: BaseAuditTimestamps = timestamps

        assertEquals(Instant.parse("2026-06-09T01:02:03.123456Z"), commonTimestamps.createdAt)
        assertEquals(Instant.parse("2026-06-09T01:02:03.123456Z"), commonTimestamps.updatedAt)
        assertEquals(null, commonTimestamps.deletedAt)
        assertColumn("createdAt", "created_at")
        assertColumn("updatedAt", "updated_at")
        assertColumn("deletedAt", "deleted_at")
    }

    @Test
    fun `jdbc audit callback marks new aggregates as created and existing aggregates as updated`() {
        val createCallback = JdbcAuditBeforeConvertCallback(
            TimeProvider.fixed(Instant.parse("2026-06-09T02:00:00.123456789Z")),
        )
        val initial = AuditTimestamps.now(TimeProvider.fixed(Instant.parse("2026-06-09T01:00:00Z")))

        val created = createCallback.onBeforeConvert(SampleJdbcEntity(id = null, audit = initial)) as SampleJdbcEntity

        assertEquals(Instant.parse("2026-06-09T02:00:00.123456Z"), created.audit.createdAt)
        assertEquals(Instant.parse("2026-06-09T02:00:00.123456Z"), created.audit.updatedAt)

        val updateCallback = JdbcAuditBeforeConvertCallback(
            TimeProvider.fixed(Instant.parse("2026-06-09T03:00:00.654321987Z")),
        )

        val updated = updateCallback.onBeforeConvert(
            SampleJdbcEntity(id = 1, audit = created.audit),
        ) as SampleJdbcEntity

        assertEquals(Instant.parse("2026-06-09T02:00:00.123456Z"), updated.audit.createdAt)
        assertEquals(Instant.parse("2026-06-09T03:00:00.654321Z"), updated.audit.updatedAt)
    }

    private data class SampleJdbcEntity(
        val id: Long?,
        override val audit: AuditTimestamps,
    ) : JdbcAuditable {
        override val isNew: Boolean
            get() = id == null

        override fun withAudit(audit: AuditTimestamps): SampleJdbcEntity =
            copy(audit = audit)
    }

    private fun assertColumn(fieldName: String, columnName: String) {
        val column = AuditTimestamps::class.java.getDeclaredField(fieldName).getAnnotation(Column::class.java)

        assertNotNull(column)
        assertEquals(columnName, column.value)
    }
}
