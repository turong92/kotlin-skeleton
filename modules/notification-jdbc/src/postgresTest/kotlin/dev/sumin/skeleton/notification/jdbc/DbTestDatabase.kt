package dev.sumin.skeleton.notification.jdbc

import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import dev.sumin.skeleton.persistence.postgresql.PostgresSqlDialect
import javax.sql.DataSource
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

object DbTestDatabase {
    const val vendor = "postgresql"
    val dialect: SqlDialect = PostgresSqlDialect()
    // by lazy: 컨테이너 시작이 한 번 실패해도(부하 · 도커 지연) 객체 초기화가 영구히 망가지지 않고 다음 접근에서 다시 시도한다 (docs/testing.md)
    private val container: PostgreSQLContainer by lazy { PostgreSQLContainer(DockerImageName.parse("postgres:18")).also { it.start() } }
    fun dataSource(): DataSource = DriverManagerDataSource(container.jdbcUrl, container.username, container.password)
}
