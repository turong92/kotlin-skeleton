package dev.sumin.skeleton.board.jdbc

import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import dev.sumin.skeleton.persistence.mysql.MySqlSqlDialect
import javax.sql.DataSource
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.utility.DockerImageName

object DbTestDatabase {
    const val vendor = "mysql"
    val dialect: SqlDialect = MySqlSqlDialect()
    private val container = MySQLContainer(DockerImageName.parse("mysql:8.4")).also { it.start() }
    fun dataSource(): DataSource = DriverManagerDataSource(
        container.jdbcUrl + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&preserveInstants=false",
        container.username,
        container.password,
    )
}
