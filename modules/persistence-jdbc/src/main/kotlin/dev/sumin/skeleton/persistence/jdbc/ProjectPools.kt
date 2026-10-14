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
        pool.jdbcUrl = details.jdbcUrl
        pool.username = project.datasource.username ?: details.username
        pool.password = if (project.datasource.username != null) project.datasource.password.orEmpty() else details.password
        pool.poolName = "project-${project.id}"
        pool.minimumIdle = 0
        pool.maximumPoolSize = project.datasource.poolMax
        if (dialect.schemaIsDatabase) pool.catalog = project.schema else pool.schema = project.schema
        return pool
    }
}
