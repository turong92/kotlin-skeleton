package dev.sumin.skeleton.app.sample.upgrade

import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.utility.DockerImageName

/** JVM 하나에 MySQL 컨테이너 하나, 시험마다 그 안의 새 데이터베이스 하나 (이유: `TestContainerRulesTest` · 샘플 앱의 TestcontainersConfiguration). */
object SharedMySql {
    private const val ROOT = "root"   // MySQLContainer 는 root 비밀번호를 사용자 비밀번호와 같게 둔다

    private val container: MySQLContainer by lazy {
        MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withCommand("--max-connections=500", "--log-bin-trust-function-creators=1")   // legal-jdbc 의 트리거 마이그레이션 (docs/modules/legal-jdbc.md)
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
