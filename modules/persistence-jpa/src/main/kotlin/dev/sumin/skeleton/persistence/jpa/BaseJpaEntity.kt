package dev.sumin.skeleton.persistence.jpa

import dev.sumin.skeleton.common.time.TimeProvider
import jakarta.persistence.Embedded
import jakarta.persistence.MappedSuperclass
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate

@MappedSuperclass
abstract class BaseJpaEntity {
    @field:Embedded
    var audit: AuditTimestamps = AuditTimestamps()

    open fun timeProvider(): TimeProvider =
        TimeProvider.systemUtc()

    @PrePersist
    fun prePersistAudit() {
        audit.markCreated(timeProvider())
    }

    @PreUpdate
    fun preUpdateAudit() {
        audit.markUpdated(timeProvider())
    }
}
