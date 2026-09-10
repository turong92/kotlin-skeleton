package dev.sumin.skeleton.persistence

import dev.sumin.skeleton.TestcontainersConfiguration
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
 * persistence-jdbc 의 UTC 고정이 실제 MySQL 에서 JVM 기본 시간대와 무관한지.
 * JVM 을 서울로 놓고 저장 → 원문(DB 리터럴)과 재조회 값이 모두 UTC 기준으로 정확해야 한다.
 * (실측: Connector/J 는 Instant/Date 파라미터를 시간대 변환하고, DATETIME 은 LocalDateTime 리터럴로 읽는다)
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class UtcRoundTripIntegrationTest {
    @Autowired
    lateinit var probes: UtcProbeRepository

    @Autowired
    lateinit var jdbc: JdbcClient

    @Test
    fun `Instant, LocalDate, LocalDateTime 이 서울 JVM 에서도 리터럴 그대로 왕복한다`() {
        val at = Instant.parse("2026-03-01T12:00:00.123456Z")
        val saved = probes.save(UtcProbe(happenedAt = at, birthday = LocalDate.of(1998, 3, 5), localWall = LocalDateTime.parse("2026-10-05T21:00")))
        val reloaded = probes.findById(saved.id!!).get()

        assertEquals(at, reloaded.happenedAt)
        assertEquals(LocalDate.of(1998, 3, 5), reloaded.birthday)
        assertEquals(LocalDateTime.parse("2026-10-05T21:00"), reloaded.localWall)

        val raw = jdbc.sql("SELECT CONCAT(happened_at, '|', birthday, '|', local_wall) FROM utc_probe WHERE id = :id")
            .param("id", saved.id).query(String::class.java).single()
        assertEquals("2026-03-01 12:00:00.123456|1998-03-05|2026-10-05 21:00:00.000000", raw)
        assertEquals("+00:00", jdbc.sql("SELECT @@session.time_zone").query(String::class.java).single())
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
