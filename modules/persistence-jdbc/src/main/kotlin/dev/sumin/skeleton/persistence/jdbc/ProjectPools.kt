package dev.sumin.skeleton.persistence.jdbc

import com.zaxxer.hikari.HikariDataSource
import dev.sumin.skeleton.common.project.Project
import javax.sql.DataSource
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import org.springframework.core.env.Environment

/** 프로젝트 하나의 연결 풀 — 같은 서버 URL, 그 프로젝트의 스키마, 작은 최대치, 연결은 처음 쓸 때 연다 */
object ProjectPools {
    fun create(project: Project, details: JdbcConnectionDetails, dialect: SqlDialect, environment: Environment): DataSource {
        val pool = HikariDataSource()   // 인자 없는 생성자: 풀은 첫 getConnection 에서 시작한다 — 안 쓰는 프로젝트는 연결 0개
        // 공통 풀 설정(spring.datasource.hikari.*)을 먼저 — db-mysql 이 세션 UTC 설정을 여기에 넣는다
        Binder.get(environment).bind("spring.datasource.hikari", Bindable.ofInstance(pool))
        // 프로젝트의 기본 스키마는 **계정**이 정한다 — 공통 hikari 설정이 연결마다 스키마를 덮어쓰면(PgBouncer 트랜잭션 모드는 따라가지도 못한다) 프로젝트끼리 섞일 수 있다
        val currentSchema = pool.dataSourceProperties.keys.map { it.toString() }.filter { it.equals("currentSchema", true) || it.equals("options", true) }
        val common = listOfNotNull(
            "spring.datasource.hikari.schema".takeIf { pool.schema != null },
            "spring.datasource.hikari.connection-init-sql".takeIf { pool.connectionInitSql != null },
        ) + currentSchema.map { "spring.datasource.hikari.data-source-properties.$it" }
        check(common.isEmpty()) { "With skeleton.projects the default schema of a project comes from its database account, but the shared pool settings choose a schema per connection: remove ${common.joinToString()} (docs/multi-project.md)." }
        pool.jdbcUrl = details.jdbcUrl
        pool.username = project.datasource.username ?: details.username
        pool.password = if (project.datasource.username != null) project.datasource.password.orEmpty() else details.password
        pool.poolName = "project-${project.id}"
        pool.minimumIdle = 0
        pool.maximumPoolSize = project.datasource.poolMax
        // PostgreSQL 은 pool.schema 를 걸지 않는다 — 연결 단위 SET search_path 는 PgBouncer 트랜잭션 모드를 따라가지 못한다. 기본 스키마는 계정에 박혀 있고 기동 때 ProjectAccountsVerifier 가 확인한다
        if (dialect.schemaIsDatabase) pool.catalog = project.schema
        return pool
    }
}
