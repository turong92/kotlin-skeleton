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
    private val container = PostgreSQLContainer(DockerImageName.parse("postgres:18")).also { it.start() }
    fun dataSource(): DataSource = DriverManagerDataSource(container.jdbcUrl, container.username, container.password)
}
