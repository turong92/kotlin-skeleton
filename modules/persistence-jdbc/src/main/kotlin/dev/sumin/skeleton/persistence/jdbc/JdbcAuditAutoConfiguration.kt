package dev.sumin.skeleton.persistence.jdbc

import dev.sumin.skeleton.common.time.TimeProvider
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

@AutoConfiguration
class JdbcAuditAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun jdbcAuditBeforeConvertCallback(
        timeProvider: ObjectProvider<TimeProvider>,
    ): JdbcAuditBeforeConvertCallback =
        JdbcAuditBeforeConvertCallback(
            timeProvider.getIfAvailable { TimeProvider.systemUtc() },
        )
}
