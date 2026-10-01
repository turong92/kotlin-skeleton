package dev.sumin.skeleton.persistence.postgresql

import java.time.Instant
import java.time.LocalDate
import java.util.TimeZone
import kotlin.test.assertEquals
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

class PostgresTimeRoundTripTest {
    private val dialect = PostgresSqlDialect()
    private val jdbc = JdbcClient.create(DriverManagerDataSource(db.jdbcUrl, db.username, db.password))

    @Test
    fun `instant written through the dialect stays UTC and LocalDate does not shift in a Seoul JVM`() {
        jdbc.sql("create table if not exists t (id int primary key, at timestamptz, d date)").update()
        val at = Instant.parse("2026-03-01T00:30:00Z") // 서울 09:30 — 날짜 경계 근처
        jdbc.sql("insert into t (id, at, d) values (1, :at, :d)")
            .param("at", dialect.instantParam(at)).param("d", LocalDate.of(2026, 3, 1)).update()

        val read = jdbc.sql("select at from t where id = 1").query { rs, _ -> dialect.readInstant(rs, "at") }.single()
        val raw = jdbc.sql("select (at at time zone 'UTC')::text || '|' || d::text from t where id = 1").query(String::class.java).single()
        assertEquals(at, read)
        assertEquals("2026-03-01 00:30:00|2026-03-01", raw)
    }

    @Test
    fun `insertIgnore does nothing on conflict and keeps the transaction usable`() {
        jdbc.sql("create table if not exists u (k varchar(10) primary key, v int)").update()
        val sql = dialect.insertIgnore("u", listOf("k", "v"), listOf("k"))
        assertEquals("insert into u (k, v) values (:k, :v) on conflict (k) do nothing", sql)
        jdbc.sql(sql).param("k", "a").param("v", 1).update()
        assertEquals(0, jdbc.sql(sql).param("k", "a").param("v", 2).update())
        assertEquals(1, jdbc.sql("select v from u where k = 'a'").query(Int::class.java).single())
    }

    companion object {
        val db = PostgreSQLContainer(DockerImageName.parse("postgres:18"))
        private lateinit var original: TimeZone

        @JvmStatic @BeforeAll
        fun start() {
            original = TimeZone.getDefault()
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
            db.start()
        }

        @JvmStatic @AfterAll
        fun stop() {
            db.stop()
            TimeZone.setDefault(original)
        }
    }
}
