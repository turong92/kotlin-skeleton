package dev.sumin.skeleton.persistence.jooq

import dev.sumin.skeleton.common.time.TimeProvider
import org.jooq.impl.DefaultRecordListenerProvider
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.jooq.autoconfigure.DefaultConfigurationCustomizer
import org.springframework.context.annotation.Bean

/**
 * `modules:persistence-jooq` 진입점. Boot 의 jOOQ 자동설정(DSLContext) 위에
 * - audit 리스너(created_at/updated_at 자동)
 * 를 [DefaultConfigurationCustomizer] 로 얹는다. UTC 세션은 [JooqTimeZoneEnvironmentPostProcessor].
 */
@AutoConfiguration
@ConditionalOnClass(name = ["org.jooq.DSLContext"])
class JooqAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun jooqAuditRecordListener(timeProvider: ObjectProvider<TimeProvider>) =
        JooqAuditRecordListener(timeProvider.getIfAvailable { TimeProvider.systemUtc() })

    @Bean
    fun skeletonJooqAuditCustomizer(listener: JooqAuditRecordListener): DefaultConfigurationCustomizer =
        DefaultConfigurationCustomizer { config -> config.set(DefaultRecordListenerProvider(listener)) }
}
