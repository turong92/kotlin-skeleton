package dev.sumin.skeleton.persistence.jdbc

import com.zaxxer.hikari.HikariDataSource
import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.common.project.ProjectsConfigured
import javax.sql.DataSource
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional
import org.springframework.core.env.Environment

/**
 * `skeleton.projects` 가 있으면 Boot 의 단일 DataSource 대신 **프로젝트로 가르는 DataSource** 를 낸다 (프로젝트마다 풀 하나, 같은 서버 · 다른 스키마).
 * 목록이 비어 있으면 이 클래스는 꺼져 있고 Boot 의 DataSource 가 그대로다. 저장소 코드는 DataSource 를 주입받는 그대로 쓴다.
 */
@AutoConfiguration(beforeName = ["org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"])
@Conditional(ProjectsConfigured::class)
@ConditionalOnClass(HikariDataSource::class)
class ProjectDataSourceAutoConfiguration {
    /** 서버 연결 정보 — Boot 가 만드는 것이 이 DataSource 때문에 빠지므로 `spring.datasource.*` 에서 직접 만든다 (시험은 자기 것을 넣는다) */
    @Bean
    @ConditionalOnMissingBean
    fun projectsJdbcConnectionDetails(environment: Environment): JdbcConnectionDetails = object : JdbcConnectionDetails {
        override fun getUsername(): String = environment.getProperty("spring.datasource.username").orEmpty()
        override fun getPassword(): String = environment.getProperty("spring.datasource.password").orEmpty()
        override fun getJdbcUrl(): String = requireNotNull(environment.getProperty("spring.datasource.url")) { "spring.datasource.url is required with skeleton.projects" }
    }

    @Bean
    @ConditionalOnMissingBean(DataSource::class)
    fun projectRoutingDataSource(
        registry: ProjectRegistry,
        details: JdbcConnectionDetails,
        dialects: ObjectProvider<SqlDialect>,
        environment: Environment,
    ): DataSource {
        // 방언이 정확히 하나인지는 SqlDialectVerifier 가 따로 본다 — 여기서는 스키마를 고르는 방법만 필요하다
        val dialect = dialects.orderedStream().findFirst().orElseThrow { IllegalStateException("No SqlDialect. Add exactly one of modules:db-postgresql / modules:db-mysql to the app.") }
        return ProjectRoutingDataSource(registry.active) { ProjectPools.create(it, details, dialect, environment) }
    }

    /** 프로젝트 모드의 계정 규칙 확인 — 마이그레이션까지 끝난 뒤(모든 싱글턴이 만들어진 직후) 돈다 */
    @Bean
    @ConditionalOnMissingBean(name = ["projectAccountsVerifier"])
    fun projectAccountsVerifier(registry: ProjectRegistry, details: JdbcConnectionDetails, dialects: ObjectProvider<SqlDialect>, dataSource: DataSource): ProjectAccountsVerifier =
        ProjectAccountsVerifier(registry.active, details, dialects.orderedStream().findFirst().orElseThrow { IllegalStateException("No SqlDialect") }.vendor) { dataSource.connection }
}
