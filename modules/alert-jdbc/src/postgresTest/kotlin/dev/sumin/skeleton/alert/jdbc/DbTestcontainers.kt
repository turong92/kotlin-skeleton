package dev.sumin.skeleton.alert.jdbc

import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/** JVM 하나에 PostgreSQL 컨테이너 하나, 컨텍스트마다 그 안의 새 데이터베이스 하나 (이유: 앱의 TestcontainersConfiguration · `TestContainerRulesTest`). */
@TestConfiguration(proxyBeanMethods = false)
class DbTestcontainers {
    @Bean
    fun db(): JdbcConnectionDetails = SharedPostgres.newDatabase()
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
