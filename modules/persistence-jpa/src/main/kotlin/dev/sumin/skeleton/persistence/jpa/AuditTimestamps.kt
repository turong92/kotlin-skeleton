package dev.sumin.skeleton.persistence.jpa

import dev.sumin.skeleton.common.audit.BaseAuditTimestamps
import dev.sumin.skeleton.common.time.TimeProvider
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import java.time.Instant

@Embeddable
open class AuditTimestamps(
    @field:Column(name = "created_at", nullable = false, columnDefinition = "DATETIME(6)")
    override var createdAt: Instant = TimeProvider.systemUtc().now(),
    @field:Column(name = "updated_at", nullable = false, columnDefinition = "DATETIME(6)")
    override var updatedAt: Instant = createdAt,
    @field:Column(name = "deleted_at", nullable = true, columnDefinition = "DATETIME(6)")
    override var deletedAt: Instant? = null,
) : BaseAuditTimestamps {
    fun markCreated(timeProvider: TimeProvider = TimeProvider.systemUtc()) {
        val now = timeProvider.now()
        createdAt = now
        updatedAt = now
    }

    fun markUpdated(timeProvider: TimeProvider = TimeProvider.systemUtc()) {
        updatedAt = timeProvider.now()
    }

    fun markDeleted(timeProvider: TimeProvider = TimeProvider.systemUtc()) {
        deletedAt = timeProvider.now()
    }

    companion object {
        fun now(timeProvider: TimeProvider = TimeProvider.systemUtc()): AuditTimestamps {
            val now = timeProvider.now()
            return AuditTimestamps(createdAt = now, updatedAt = now)
        }
    }
}
