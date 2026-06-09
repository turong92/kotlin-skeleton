package dev.sumin.skeleton.common.audit

import java.time.Instant

interface BaseAuditTimestamps {
    val createdAt: Instant
    val updatedAt: Instant
    val deletedAt: Instant?
}
