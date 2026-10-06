package dev.sumin.skeleton.legal.jdbc

import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.utility.DockerImageName

/** JVM 하나에 MySQL 컨테이너 하나, 컨텍스트마다 그 안의 새 데이터베이스 하나 (이유: 앱의 TestcontainersConfiguration · `TestContainerRulesTest`). */
@TestConfiguration(proxyBeanMethods = false)
class DbTestcontainers {
    @Bean
    fun db(): JdbcConnectionDetails = SharedMySql.newDatabase()
}

object SharedMySql {
    private const val ROOT = "root"   // MySQLContainer 는 root 비밀번호를 사용자 비밀번호와 같게 둔다

    private val container: MySQLContainer by lazy {
        MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withCommand("--max-connections=500", "--log-bin-trust-function-creators=1")   // 트리거를 만들려면 필요하다 (binlog 가 켜진 MySQL 에서 SUPER 가 없을 때) — docs/modules/legal-jdbc.md
            .also { it.start() }
    }
    private val sequence = AtomicInteger()

    @Synchronized
    fun newDatabase(): JdbcConnectionDetails {
        val name = "ctx_${sequence.incrementAndGet()}_${UUID.randomUUID().toString().take(8)}"
        val pattern = Regex("/" + Regex.escape(container.databaseName) + "(?=\\?|$)")
        DriverManager.getConnection(container.jdbcUrl.replaceFirst(pattern, "/"), ROOT, container.password).use { connection ->
            connection.createStatement().use {
                it.execute("create database `$name`")
                it.execute("grant all on `$name`.* to '${container.username}'@'%'")
            }
        }
        val url = container.jdbcUrl.replaceFirst(pattern, "/$name")
        return object : JdbcConnectionDetails {
            override fun getUsername(): String = container.username
            override fun getPassword(): String = container.password
            override fun getJdbcUrl(): String = url
        }
    }
}
