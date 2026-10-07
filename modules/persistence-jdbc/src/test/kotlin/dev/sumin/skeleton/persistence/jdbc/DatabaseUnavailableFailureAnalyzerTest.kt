package dev.sumin.skeleton.persistence.jdbc

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.sql.SQLException
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.BeanCreationException
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.beans.factory.UnsatisfiedDependencyException
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.jdbc.CannotGetJdbcConnectionException

/** 기동할 때 DB 에 못 닿는 것(아직 초기화 중 · 주소 틀림 · 망)을 읽을 수 있는 말로 — 실제 배포에서 스택 트레이스(JdbcAggregateOperations)만 보였던 자리 */
class DatabaseUnavailableFailureAnalyzerTest {
    private fun env(url: String?) = StandardEnvironment().also { e ->
        if (url != null) e.propertySources.addFirst(MapPropertySource("t", mapOf("spring.datasource.url" to url)))
    }

    private fun analyzer(url: String? = "jdbc:postgresql://db:5432/app?user=app&password=hunter2") = DatabaseUnavailableFailureAnalyzer(env(url))

    /** Spring 이 보여 주는 모양: 빈 생성 실패가 겹겹이 싸고 있고 맨 밑이 연결 실패 */
    private fun wrapped(root: Throwable): Throwable =
        UnsatisfiedDependencyException("AppConfig", "jdbcAggregateOperations", "dataSource", BeanCreationException("Error creating bean with name 'jdbcAggregateOperations'", CannotGetJdbcConnectionException("Failed to obtain JDBC Connection", root as? SQLException ?: SQLException(root))))

    @Test
    fun `connection refused names host and port of the configured URL and the likely causes, never the credentials`() {
        val refused = SQLException("Connection to db:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.", "08001", ConnectException("Connection refused"))
        val analysis = assertNotNull(analyzer().analyze(wrapped(refused)))
        assertThat(analysis.description).contains("db:5432").contains("refused")
        assertThat(analysis.action).contains("still starting").contains("SPRING_DATASOURCE_URL").contains("network")
        assertThat(analysis.action).contains("startup-wait")
        val all = analysis.description + analysis.action
        assertThat(all).doesNotContain("hunter2").doesNotContain("password").doesNotContain("user=app")
    }

    @Test
    fun `an unknown host is said plainly`() {
        val analysis = assertNotNull(analyzer("jdbc:postgresql://late-db:5432/app").analyze(wrapped(SQLException("The connection attempt failed.", "08001", UnknownHostException("late-db")))))
        assertThat(analysis.description).contains("late-db:5432").contains("unknown host")
    }

    @Test
    fun `a server that is starting up (PostgreSQL 57P03) and a connect timeout are the same family`() {
        val starting = assertNotNull(analyzer().analyze(wrapped(SQLException("FATAL: the database system is starting up", "57P03"))))
        assertThat(starting.description).contains("db:5432").contains("starting up")
        val timeout = assertNotNull(analyzer().analyze(wrapped(SQLException("The connection attempt failed.", "08001", SocketTimeoutException("Connect timed out")))))
        assertThat(timeout.description).contains("db:5432").contains("timed out")
    }

    @Test
    fun `host and port come from the driver message when the URL is not in the environment, and the default port from the vendor`() {
        val fromMessage = assertNotNull(analyzer(null).analyze(wrapped(SQLException("Connection to pg.internal:6543 refused.", "08001", ConnectException("refused")))))
        assertThat(fromMessage.description).contains("pg.internal:6543")
        val mysql = assertNotNull(analyzer("jdbc:mysql://mysql-host/app").analyze(wrapped(SQLException("Communications link failure", "08S01", ConnectException("Connection refused")))))
        assertThat(mysql.description).contains("mysql-host:3306")
    }

    @Test
    fun `the wait giving up is explained with how long it waited`() {
        val gaveUp = DatabaseNotReachableException(target = "db:5432", waited = java.time.Duration.ofSeconds(60), cause = SQLException("Connection to db:5432 refused.", "08001", ConnectException("refused")))
        val analysis = assertNotNull(analyzer(null).analyze(gaveUp))
        assertThat(analysis.description).contains("db:5432").contains("60s")
    }

    @Test
    fun `other failures are left alone - a wrong password, a missing dialect, anything else`() {
        assertNull(analyzer().analyze(wrapped(SQLException("FATAL: password authentication failed for user \"app\"", "28P01"))))
        assertNull(analyzer().analyze(NoSuchBeanDefinitionException(SqlDialect::class.java)))
        assertNull(analyzer().analyze(IllegalStateException("boom")))
        assertNull(SqlDialectFailureAnalyzer().analyze(wrapped(SQLException("Connection to db:5432 refused.", "08001", ConnectException("refused")))), "the missing-dialect analyzer does not claim connection failures")
    }

    @Test
    fun `the analyzer registered in spring factories gets the environment the way Spring Boot hands it over (constructor argument, not an Aware callback)`() {
        val loaded = org.springframework.core.io.support.SpringFactoriesLoader.forDefaultResourceLocation(javaClass.classLoader)
            .load(org.springframework.boot.diagnostics.FailureAnalyzer::class.java, org.springframework.core.io.support.SpringFactoriesLoader.ArgumentResolver.of(org.springframework.core.env.Environment::class.java, env("jdbc:postgresql://nowhere-db:5432/app?user=leakme&password=leak-pw-123")).and(org.springframework.beans.factory.BeanFactory::class.java, org.springframework.beans.factory.support.DefaultListableBeanFactory()))   // Boot 의 FailureAnalyzers 와 같은 두 인자
            .filterIsInstance<DatabaseUnavailableFailureAnalyzer>().single()
        val analysis = assertNotNull(loaded.analyze(wrapped(SQLException("The connection attempt failed.", "08001", UnknownHostException("nowhere-db")))))
        assertThat(analysis.description).contains("nowhere-db:5432").contains("unknown host").doesNotContain("leak")
    }

    @Test
    fun `without a context Spring Boot creates the registered analyzer with no arguments, and the wait giving up is still explained`() {
        val loaded = org.springframework.core.io.support.SpringFactoriesLoader.forDefaultResourceLocation(javaClass.classLoader)
            .load(org.springframework.boot.diagnostics.FailureAnalyzer::class.java, org.springframework.core.io.support.SpringFactoriesLoader.ArgumentResolver.none(), org.springframework.core.io.support.SpringFactoriesLoader.FailureHandler.logging(org.apache.commons.logging.LogFactory.getLog(javaClass)))   // Boot 자신의 분석기는 인자 없이 못 만들어 건너뛴다 — Boot 와 같은 방식
            .filterIsInstance<DatabaseUnavailableFailureAnalyzer>().single()
        val gaveUp = DatabaseNotReachableException(target = "nowhere-db:5432", waited = java.time.Duration.ofSeconds(6), cause = SQLException("x", "08001", UnknownHostException("nowhere-db")))
        val analysis = assertNotNull(loaded.analyze(gaveUp))
        assertThat(analysis.description).contains("nowhere-db:5432").contains("unknown host").contains("6s")
    }
}
