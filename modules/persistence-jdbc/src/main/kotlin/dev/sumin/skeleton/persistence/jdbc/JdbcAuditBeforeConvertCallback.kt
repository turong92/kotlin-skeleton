package dev.sumin.skeleton.persistence.jdbc

import dev.sumin.skeleton.common.time.TimeProvider
import org.springframework.data.relational.core.mapping.event.BeforeConvertCallback

class JdbcAuditBeforeConvertCallback(
    private val timeProvider: TimeProvider = TimeProvider.systemUtc(),
) : BeforeConvertCallback<Any> {
    override fun onBeforeConvert(entity: Any): Any {
        if (entity !is JdbcAuditable) {
            return entity
        }

        val nextAudit = if (entity.isNew) {
            entity.audit.markCreated(timeProvider)
        } else {
            entity.audit.markUpdated(timeProvider)
        }

        return entity.withAudit(nextAudit)
    }
}
