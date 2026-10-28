package dev.sumin.skeleton.persistence.mysql

import java.time.Instant
import java.time.LocalDate
import java.util.TimeZone
import kotlin.test.assertEquals
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.utility.DockerImageName

class MySqlTimeRoundTripTest {
    private val dialect = MySqlSqlDialect()
    // 드라이버 속성은 MySqlTimeZoneEnvironmentPostProcessor 가 Hikari 에 넣는 것과 같게
    private val jdbc = JdbcClient.create(
        DriverManagerDataSource(
            db.jdbcUrl + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&preserveInstants=false",
            db.username,
            db.password,
        ),
    )

    @Test
    fun `instant written through the dialect is stored as a UTC literal in a Seoul JVM`() {
        jdbc.sql("create table if not exists t (id int primary key, at datetime(6), d date)").update()
        val at = Instant.parse("2026-03-01T00:30:00Z")
        jdbc.sql("insert into t (id, at, d) values (1, :at, :d)")
            .param("at", dialect.instantParam(at)).param("d", LocalDate.of(2026, 3, 1)).update()

        val read = jdbc.sql("select at from t where id = 1").query { rs, _ -> dialect.readInstant(rs, "at") }.single()
        val raw = jdbc.sql("select concat(at, '|', d) from t where id = 1").query(String::class.java).single()
        assertEquals(at, read)
        assertEquals("2026-03-01 00:30:00.000000|2026-03-01", raw)
    }

    @Test
    fun `insertIgnore does nothing on duplicate key`() {
        jdbc.sql("create table if not exists u (k varchar(10) primary key, v int)").update()
        val sql = dialect.insertIgnore("u", listOf("k", "v"), listOf("k"))
        assertEquals("insert into u (k, v) values (:k, :v) on duplicate key update k = k", sql)
        jdbc.sql(sql).param("k", "a").param("v", 1).update()
        jdbc.sql(sql).param("k", "a").param("v", 2).update()
        assertEquals(1, jdbc.sql("select v from u where k = 'a'").query(Int::class.java).single())
    }

    @Test
    fun `insertIgnore with no conflict columns ignores a conflict on any unique key`() {
        jdbc.sql("create table if not exists u2 (a varchar(10), b varchar(10), v int, primary key (a), unique key uk_b (b))").update()
        val sql = dialect.insertIgnore("u2", listOf("a", "b", "v"), emptyList())
        assertEquals("insert into u2 (a, b, v) values (:a, :b, :v) on duplicate key update a = a", sql)
        jdbc.sql(sql).param("a", "1").param("b", "x").param("v", 1).update()
        jdbc.sql(sql).param("a", "1").param("b", "y").param("v", 2).update()   // 기본 키 충돌
        jdbc.sql(sql).param("a", "2").param("b", "x").param("v", 3).update()   // 유니크 충돌
        assertEquals(1, jdbc.sql("select count(*) from u2").query(Int::class.java).single())
    }

    companion object {
        val db = MySQLContainer(DockerImageName.parse("mysql:8.4"))
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
