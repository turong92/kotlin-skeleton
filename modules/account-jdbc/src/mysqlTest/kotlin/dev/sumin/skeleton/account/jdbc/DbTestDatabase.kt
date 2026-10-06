package dev.sumin.skeleton.account.jdbc

import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import dev.sumin.skeleton.persistence.mysql.MySqlSqlDialect
import javax.sql.DataSource
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.utility.DockerImageName

object DbTestDatabase {
    const val vendor = "mysql"
    val dialect: SqlDialect = MySqlSqlDialect()
    // by lazy: 컨테이너 시작이 한 번 실패해도(부하 · 도커 지연) 객체 초기화가 영구히 망가지지 않고 다음 접근에서 다시 시도한다 (docs/testing.md)
    private val container: MySQLContainer by lazy { MySQLContainer(DockerImageName.parse("mysql:8.4")).also { it.start() } }
    fun dataSource(): DataSource = DriverManagerDataSource(
        container.jdbcUrl + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&preserveInstants=false",
        container.username,
        container.password,
    )
}
