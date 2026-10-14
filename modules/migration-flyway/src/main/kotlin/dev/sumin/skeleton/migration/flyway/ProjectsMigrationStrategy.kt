package dev.sumin.skeleton.migration.flyway

import dev.sumin.skeleton.common.project.Project
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails

/**
 * 프로젝트가 있으면 기동 때 **스키마마다** 같은 스크립트를 돌린다 — 이력(`flyway_schema_history`)도 스키마마다. 하나라도 실패하면 기동이 실패한다(반쯤 뜬 서버를 만들지 않는다).
 * Boot 가 만든 Flyway 의 설정(위치 · out-of-order · …)을 그대로 쓰고 연결과 스키마만 바꾼다. 연결은 서버 연결 정보에 프로젝트 DB 계정이 있으면 그것으로 —
 * 스키마가 없으면 Flyway 가 만든다(DB 계정에 권한이 없으면 운영자가 미리 만든다).
 * [perProject] 는 프로젝트 하나의 Flyway 를 어떻게 돌릴지 — 기본은 `migrate()`, 로컬 clean 옵션이 켜져 있으면 그 전략.
 */
class ProjectsMigrationStrategy(
    private val projects: List<Project>,
    private val details: JdbcConnectionDetails,
    private val perProject: FlywayMigrationStrategy = FlywayMigrationStrategy { it.migrate() },
) : FlywayMigrationStrategy {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun migrate(flyway: Flyway) {
        projects.forEach { project ->
            val own = project.datasource.username
            val scoped = Flyway.configure(flyway.configuration.classLoader)
                .configuration(flyway.configuration)
                .dataSource(details.jdbcUrl, own ?: details.username, if (own != null) project.datasource.password.orEmpty() else details.password)
                .schemas(project.schema)
                .defaultSchema(project.schema)
                .load()
            try {
                perProject.migrate(scoped)
            } catch (e: Exception) {
                throw IllegalStateException("Migration of project '${project.id}' (schema '${project.schema}') failed: ${e.message}", e)
            }
            log.info("Migrated project '{}' (schema '{}')", project.id, project.schema)
        }
    }
}
