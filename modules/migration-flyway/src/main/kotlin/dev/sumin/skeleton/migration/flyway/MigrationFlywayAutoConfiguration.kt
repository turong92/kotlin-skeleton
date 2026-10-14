package dev.sumin.skeleton.migration.flyway

import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.common.project.ProjectsConfigured
import dev.sumin.skeleton.migration.MigrationProperties
import org.flywaydb.core.Flyway
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy
import org.springframework.boot.flyway.autoconfigure.FlywayConnectionDetails
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional
import org.springframework.core.env.Environment

/** 가드는 공통 migration 모듈의 MigrationCleanGuardEnvironmentPostProcessor 가 (빈보다 먼저) 처리한다. 여기는 Flyway 용 옵트인 로컬 clean 전략만. */
@AutoConfiguration(before = [FlywayAutoConfiguration::class])
@ConditionalOnClass(Flyway::class)
@EnableConfigurationProperties(MigrationProperties::class)
class MigrationFlywayAutoConfiguration {
    /**
     * `skeleton.projects` 가 있을 때: Boot 의 Flyway 는 프로젝트로 가르는 DataSource 가 아니라 서버 연결(스키마 지정 없음)로 만들게 하고
     * (`{vendor}` 폴더도 거기서 정한다), 실제 마이그레이션은 [ProjectsMigrationStrategy] 가 스키마마다 돈다.
     * 로컬 clean 옵션이 켜져 있으면 프로젝트마다 그 전략을 거친다.
     */
    @Conditional(ProjectsConfigured::class)
    class Projects {
        @Bean
        @ConditionalOnMissingBean(FlywayConnectionDetails::class)
        fun projectsFlywayConnectionDetails(details: JdbcConnectionDetails): FlywayConnectionDetails = object : FlywayConnectionDetails {
            override fun getUsername(): String? = details.username
            override fun getPassword(): String? = details.password
            override fun getJdbcUrl(): String = details.jdbcUrl
        }

        @Bean
        @ConditionalOnMissingBean(FlywayMigrationStrategy::class)
        fun projectsMigrationStrategy(registry: ProjectRegistry, details: JdbcConnectionDetails, environment: Environment): FlywayMigrationStrategy =
            ProjectsMigrationStrategy(
                registry.active, details,
                if (environment.getProperty("skeleton.migration.clean-on-validation-error", Boolean::class.java, false)) CleanOnValidationErrorMigrationStrategy() else FlywayMigrationStrategy { it.migrate() },
            )
    }

    @Bean
    @ConditionalOnMissingBean(FlywayMigrationStrategy::class)
    @ConditionalOnProperty(prefix = "skeleton.migration", name = ["clean-on-validation-error"], havingValue = "true")
    fun cleanOnValidationErrorMigrationStrategy(): FlywayMigrationStrategy = CleanOnValidationErrorMigrationStrategy()
}
