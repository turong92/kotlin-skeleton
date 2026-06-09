package dev.sumin.skeleton.persistence.jdbc

interface JdbcAuditable {
    val audit: AuditTimestamps
    val isNew: Boolean

    fun withAudit(audit: AuditTimestamps): JdbcAuditable
}
