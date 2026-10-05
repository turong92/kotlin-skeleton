package dev.sumin.skeleton.migration

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment

@AutoConfiguration
class MigrationAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun migrationCleanDeployGuard(environment: Environment): MigrationCleanDeployGuard = MigrationCleanDeployGuard(environment)
}
