package dev.sumin.skeleton.persistence.jdbc

import dev.sumin.skeleton.common.audit.BaseAuditTimestamps
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Instant
import org.springframework.data.relational.core.mapping.Column

data class AuditTimestamps(
    @field:Column("created_at")
    override val createdAt: Instant,
    @field:Column("updated_at")
    override val updatedAt: Instant,
    @field:Column("deleted_at")
    override val deletedAt: Instant? = null,
) : BaseAuditTimestamps {
    fun markCreated(timeProvider: TimeProvider = TimeProvider.systemUtc()): AuditTimestamps {
        val now = timeProvider.now()
        return copy(createdAt = now, updatedAt = now)
    }

    fun markUpdated(timeProvider: TimeProvider = TimeProvider.systemUtc()): AuditTimestamps =
        copy(updatedAt = timeProvider.now())

    fun markDeleted(timeProvider: TimeProvider = TimeProvider.systemUtc()): AuditTimestamps =
        copy(deletedAt = timeProvider.now())

    companion object {
        fun now(timeProvider: TimeProvider = TimeProvider.systemUtc()): AuditTimestamps {
            val now = timeProvider.now()
            return AuditTimestamps(createdAt = now, updatedAt = now)
        }
    }
}
