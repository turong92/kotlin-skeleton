package dev.sumin.skeleton.time

import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals

class ZonedMomentTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    @Test
    fun `현지시각+시간대가 원본이고 at 은 파생값이다`() {
        val m = ZonedMoment.of(LocalDateTime.parse("2026-10-05T21:00"), "Asia/Seoul")
        assertEquals(Instant.parse("2026-10-05T12:00:00Z"), m.at)
        assertEquals("Asia/Seoul", m.zone)
        assertEquals(LocalDateTime.parse("2026-10-05T09:00"), m.inZone(ZoneId.of("America/Sao_Paulo")).toLocalDateTime())
    }

    @Test
    fun `DST 틈 - 없는 시각은 뒤로 민다 (베를린 2026-03-29 02시30분 → 03시30분)`() {
        val m = ZonedMoment.of(LocalDateTime.parse("2026-03-29T02:30"), berlin)
        assertEquals(LocalDateTime.parse("2026-03-29T03:30"), m.atEventZone().toLocalDateTime())
        assertEquals(ZoneOffset.ofHours(2), m.atEventZone().offset)
    }

    @Test
    fun `DST 중복 - 두 번 있는 시각은 앞 오프셋(여름) 을 쓴다 (베를린 2026-10-25 02시30분)`() {
        val m = ZonedMoment.of(LocalDateTime.parse("2026-10-25T02:30"), berlin)
        assertEquals(ZoneOffset.ofHours(2), m.atEventZone().offset)
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), m.at)
    }

    @Test
    fun `DST 를 폐지한 지역도 현재 규칙대로 (상파울루 2019 이후 연중 -03)`() {
        val m = ZonedMoment.of(LocalDateTime.parse("2026-01-15T12:00"), "America/Sao_Paulo")
        assertEquals(ZoneOffset.ofHours(-3), m.atEventZone().offset)
    }

    @Test
    fun `recompute 는 local+zone 에서 at 을 다시 만든다`() {
        val stale = ZonedMoment(LocalDateTime.parse("2026-10-05T21:00"), "Asia/Seoul", Instant.EPOCH)
        assertEquals(Instant.parse("2026-10-05T12:00:00Z"), stale.recompute().at)
    }
}
