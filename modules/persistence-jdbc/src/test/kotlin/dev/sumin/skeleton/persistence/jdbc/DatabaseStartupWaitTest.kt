package dev.sumin.skeleton.persistence.jdbc

import java.net.ConnectException
import java.sql.SQLException
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

class DatabaseStartupWaitTest {
    /** 시계는 가짜 — 잠자기가 시간을 앞으로 보낸다 */
    private class FakeTime {
        var nowNanos = 0L
        val sleeps = mutableListOf<Duration>()
        fun sleep(d: Duration) { sleeps += d; nowNanos += d.toNanos() }
    }

    private val refused = { SQLException("Connection to db:5432 refused.", "08001", ConnectException("refused")) }

    @Test
    fun `retries the first connection until it works`() {
        val time = FakeTime()
        var attempts = 0
        DatabaseStartupWait.await("db:5432", Duration.ofSeconds(60), Duration.ofSeconds(2), time::sleep, { time.nowNanos }) { if (++attempts < 4) throw refused() }
        assertEquals(4, attempts)
        assertEquals(List(3) { Duration.ofSeconds(2) }, time.sleeps)
    }

    @Test
    fun `gives up after the timeout with the target and the last cause`() {
        val time = FakeTime()
        var attempts = 0
        val e = assertFailsWith<DatabaseNotReachableException> {
            DatabaseStartupWait.await("db:5432", Duration.ofSeconds(10), Duration.ofSeconds(3), time::sleep, { time.nowNanos }) { attempts++; throw refused() }
        }
        assertEquals("db:5432", e.target)
        assertEquals(Duration.ofSeconds(10), e.waited)
        assertTrue(e.cause is SQLException)
        assertTrue(attempts in 4..5, "attempts=$attempts")   // 0 · 3 · 6 · 9 (+ 마지막 시도)
    }

    @Test
    fun `a wrong password is not waited for - it fails at once`() {
        val time = FakeTime()
        assertFailsWith<SQLException> {
            DatabaseStartupWait.await("db:5432", Duration.ofSeconds(60), Duration.ofSeconds(2), time::sleep, { time.nowNanos }) { throw SQLException("FATAL: password authentication failed", "28P01") }
        }
        assertEquals(emptyList(), time.sleeps)
    }

    private fun environment(vararg props: Pair<String, String>) = StandardEnvironment().also { it.propertySources.addFirst(MapPropertySource("t", mapOf(*props))) }

    @Test
    fun `off by default - nothing is tried`() {
        var tried = false
        DatabaseStartupWaitPostProcessor { _, _, _ -> tried = true }.postProcessEnvironment(environment("spring.datasource.url" to "jdbc:postgresql://db:5432/app"), SpringApplication())
        assertEquals(false, tried)
    }

    @Test
    fun `enabled - connects with the datasource settings and waits for the configured time`() {
        var seen: Triple<String, String?, String?>? = null
        val waited = mutableListOf<String>()
        val p = DatabaseStartupWaitPostProcessor { url, user, password -> seen = Triple(url, user, password); waited += url }
        p.postProcessEnvironment(
            environment(
                "skeleton.persistence-jdbc.startup-wait.enabled" to "true", "skeleton.persistence-jdbc.startup-wait.timeout" to "45s",
                "spring.datasource.url" to "jdbc:postgresql://db:5432/app", "spring.datasource.username" to "app", "spring.datasource.password" to "pw",
            ),
            SpringApplication(),
        )
        assertEquals(Triple("jdbc:postgresql://db:5432/app", "app", "pw"), seen)
    }

    @Test
    fun `enabled without a datasource url does nothing - a test or a JdbcConnectionDetails bean decides the connection`() {
        var tried = false
        DatabaseStartupWaitPostProcessor { _, _, _ -> tried = true }.postProcessEnvironment(environment("skeleton.persistence-jdbc.startup-wait.enabled" to "true"), SpringApplication())
        assertEquals(false, tried)
    }
}
