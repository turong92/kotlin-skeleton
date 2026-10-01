package dev.sumin.skeleton.migration.flyway

import dev.sumin.skeleton.migration.MigrationProperties
import org.flywaydb.core.Flyway
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy
import org.springframework.context.annotation.Bean

/** 가드는 공통 migration 모듈의 MigrationCleanGuardEnvironmentPostProcessor 가 (빈보다 먼저) 처리한다. 여기는 Flyway 용 옵트인 로컬 clean 전략만. */
@AutoConfiguration(before = [FlywayAutoConfiguration::class])
@ConditionalOnClass(Flyway::class)
@EnableConfigurationProperties(MigrationProperties::class)
class MigrationFlywayAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(FlywayMigrationStrategy::class)
    @ConditionalOnProperty(prefix = "skeleton.migration", name = ["clean-on-validation-error"], havingValue = "true")
    fun cleanOnValidationErrorMigrationStrategy(): FlywayMigrationStrategy = CleanOnValidationErrorMigrationStrategy()
}
