package dev.sumin.skeleton.persistence.jooq

import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@SpringBootApplication
class JooqTestApplication

/** JVM 하나에 PostgreSQL 컨테이너 하나, 컨텍스트마다 그 안의 새 데이터베이스 하나 (이유: 앱의 TestcontainersConfiguration · `TestContainerRulesTest`). */
@TestConfiguration(proxyBeanMethods = false)
class JooqTestcontainers {
    /** 코드 생성에 쓴 방언(`-Pskeleton.jooq.dialect`, 기본 postgresql)의 DB — 생성물과 같은 DB 에 써야 시간 왕복이 의미가 있다 */
    @Bean
    fun db(): JdbcConnectionDetails = if (JooqDialect.current == "mysql") SharedMySql.newDatabase() else SharedPostgres.newDatabase()
}

object JooqDialect {
    val current: String = System.getProperty("skeleton.jooq.dialect", "postgresql")
}

/** JVM 하나에 MySQL 컨테이너 하나 + 컨텍스트마다 새 데이터베이스 (SharedPostgres 와 같다). 세션 시간대는 UTC 로 고정한다 — db-mysql 모듈이 앱에 하는 일과 같다 */
object SharedMySql {
    private val container: MySQLContainer by lazy { MySQLContainer(DockerImageName.parse("mysql:8.4")).also { it.start() } }

    private val sequence = AtomicInteger()

    @Synchronized
    fun newDatabase(): JdbcConnectionDetails {
        val name = "ctx_${sequence.incrementAndGet()}_${UUID.randomUUID().toString().take(8)}"
        DriverManager.getConnection(container.jdbcUrl.replaceFirst("/" + container.databaseName, "/"), "root", container.password).use { connection ->
            connection.createStatement().use { it.execute("create database `$name`") }
        }
        val url = container.jdbcUrl.replaceFirst("/" + container.databaseName, "/$name") +
            "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&preserveInstants=false"
        return object : JdbcConnectionDetails {
            override fun getUsername(): String = "root"
            override fun getPassword(): String = container.password
            override fun getJdbcUrl(): String = url
        }
    }
}

object SharedPostgres {
    private val container: PostgreSQLContainer by lazy {
        PostgreSQLContainer(DockerImageName.parse("postgres:18"))
            .withCommand("postgres", "-c", "max_connections=500", "-c", "fsync=off")
            .also { it.start() }
    }
    private val sequence = AtomicInteger()

    @Synchronized
    fun newDatabase(): JdbcConnectionDetails {
        val name = "ctx_${sequence.incrementAndGet()}_${UUID.randomUUID().toString().take(8)}"
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
            connection.createStatement().use { it.execute("create database \"$name\"") }
        }
        val url = container.jdbcUrl.replaceFirst(Regex("/" + Regex.escape(container.databaseName) + "(?=\\?|$)"), "/$name")
        return object : JdbcConnectionDetails {
            override fun getUsername(): String = container.username
            override fun getPassword(): String = container.password
            override fun getJdbcUrl(): String = url
        }
    }
}
