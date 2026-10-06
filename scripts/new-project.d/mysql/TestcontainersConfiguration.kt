package dev.sumin.skeleton.app.api

import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * 테스트 DB 의 정본 배선 — **JVM(= Gradle 테스트 포크) 하나에 MySQL 컨테이너 하나**, 스프링 컨텍스트마다 그 안에 새 데이터베이스 하나.
 *
 * 컨텍스트마다 `@Bean @ServiceConnection` 컨테이너를 띄우면 컨텍스트 캐시가 JVM 이 끝날 때까지 컨텍스트(= 컨테이너)를 전부 붙들어
 * 컨텍스트 종류만큼 컨테이너가 쌓인다. 그 모양은 `TestContainerRulesTest` 가 막는다.
 * 컨텍스트끼리의 격리는 컨테이너가 아니라 데이터베이스로 한다 — 컨텍스트마다 Flyway 가 새 데이터베이스에 처음부터 마이그레이션한다.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {
    @Bean
    fun jdbcConnectionDetails(): JdbcConnectionDetails = SharedMySql.newDatabase()
}

object SharedMySql {
    private const val ROOT = "root"   // MySQLContainer 는 root 비밀번호를 사용자 비밀번호와 같게 둔다

    private val container: MySQLContainer by lazy {
        // 컨텍스트 캐시에 살아 있는 컨텍스트마다 풀이 연결을 잡는다 — 기본 max_connections(151) 으로는 모자란다
        MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withCommand("--max-connections=500", "--log-bin-trust-function-creators=1")   // legal-jdbc 의 마이그레이션이 트리거를 만든다 — binlog 가 켜진 MySQL 에서는 이 변수나 SUPER 가 필요하다 (docs/modules/legal-jdbc.md)
            .also { it.start() }
    }
    private val sequence = AtomicInteger()

    @Synchronized
    fun newDatabase(): JdbcConnectionDetails {
        val name = "ctx_${sequence.incrementAndGet()}_${UUID.randomUUID().toString().take(8)}"
        val serverUrl = container.jdbcUrl.replaceFirst(Regex("/" + Regex.escape(container.databaseName) + "(?=\\?|$)"), "/")
        DriverManager.getConnection(serverUrl, ROOT, container.password).use { connection ->
            connection.createStatement().use {
                it.execute("create database `$name`")
                it.execute("grant all on `$name`.* to '${container.username}'@'%'")
            }
        }
        val url = container.jdbcUrl.replaceFirst(Regex("/" + Regex.escape(container.databaseName) + "(?=\\?|$)"), "/$name")
        return object : JdbcConnectionDetails {
            override fun getUsername(): String = container.username
            override fun getPassword(): String = container.password
            override fun getJdbcUrl(): String = url
        }
    }
}
