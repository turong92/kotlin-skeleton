package dev.sumin.skeleton.app.workbench.persistence

import dev.sumin.skeleton.app.workbench.TestcontainersConfiguration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.TimeZone
import kotlin.test.assertEquals

@Table("utc_probe")
data class UtcProbe(
    @Id val id: Long? = null,
    val happenedAt: Instant,
    val birthday: LocalDate?,
    val localWall: LocalDateTime?,
)

interface UtcProbeRepository : CrudRepository<UtcProbe, Long>

/**
 * db-postgresql 의 시각 고정이 JVM 기본 시간대와 무관한지.
 * JVM 을 서울로 놓고 저장 → 원문(UTC 로 본 DB 값)과 재조회 값이 모두 정확해야 한다.
 * (실측: pgjdbc 는 UTC LocalDateTime 을 세션 시간대로 해석해 −9h, Instant 는 바인딩 거부 — SqlDialect KDoc)
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class UtcRoundTripIntegrationTest {
    @Autowired
    lateinit var probes: UtcProbeRepository

    @Autowired
    lateinit var jdbc: JdbcClient

    @Test
    fun `Instant, LocalDate, LocalDateTime 이 서울 JVM 에서도 그대로 왕복한다`() {
        val at = Instant.parse("2026-03-01T00:30:00.123456Z") // 서울 09:30 — 날짜 경계
        val saved = probes.save(UtcProbe(happenedAt = at, birthday = LocalDate.of(1998, 3, 5), localWall = LocalDateTime.parse("2026-10-05T21:00")))
        val reloaded = probes.findById(saved.id!!).get()

        assertEquals(at, reloaded.happenedAt)
        assertEquals(LocalDate.of(1998, 3, 5), reloaded.birthday)
        assertEquals(LocalDateTime.parse("2026-10-05T21:00"), reloaded.localWall)

        val raw = jdbc.sql("select (happened_at at time zone 'UTC')::text || '|' || birthday::text || '|' || local_wall::text from utc_probe where id = :id")
            .param("id", saved.id).query(String::class.java).single()
        assertEquals("2026-03-01 00:30:00.123456|1998-03-05|2026-10-05 21:00:00", raw)
    }

    companion object {
        private lateinit var original: TimeZone

        @JvmStatic
        @BeforeAll
        fun seoul() {
            original = TimeZone.getDefault()
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
        }

        @JvmStatic
        @AfterAll
        fun restore() {
            TimeZone.setDefault(original)
        }
    }
}
