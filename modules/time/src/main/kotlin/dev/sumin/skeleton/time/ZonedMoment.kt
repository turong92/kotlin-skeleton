package dev.sumin.skeleton.time

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * **예정된 현지 시각** (마감, 이벤트 시작). 시각 타입 3종 중 하나:
 *
 * | 종류 | 타입 | 용도 |
 * |---|---|---|
 * | 일어난 시점 | `Instant` | createdAt, 결제 시각 — 과거 사실 |
 * | 달력 날짜 | `LocalDate` | 생일, 기념일 — 시간대 변환 안 함 |
 * | 예정된 현지 시각 | [ZonedMoment] | 마감, 이벤트 시작 — 현지시각+시간대가 원본 |
 *
 * 원본은 [local]+[zone] 이고 [at] 은 파생값이다. 미래 시각을 `Instant` 로만 저장하면 그 지역의
 * DST/시간대 규칙이 바뀌었을 때(멕시코 2022, 이집트 2023, 카자흐스탄 2024) "21:00" 이 아니게 된다.
 * [at] 은 정렬·비교·알림 스케줄용이고, 규칙이 바뀌면 [recompute] 로 다시 계산한다.
 *
 * DB: `xxx_local DATETIME(6)`, `xxx_zone VARCHAR(50)`, `xxx_at DATETIME(6)` — Data JDBC `@Embedded(prefix = "xxx_")`.
 *
 * DST 정책 ([ZonedDateTime.ofLocal] 과 동일):
 * - 없는 시각(봄, 시계 앞으로): 틈 길이만큼 뒤로 민다. 02:30 → 03:30
 * - 두 번 있는 시각(가을, 시계 뒤로): 앞 오프셋(여름 시간) 을 쓴다
 */
data class ZonedMoment(
    val local: LocalDateTime,
    /** IANA zone id. 문자열로 두는 이유: DB/JSON 에 그대로 저장·전송 */
    val zone: String,
    val at: Instant,
) {
    /** 함수인 이유: 프로퍼티면 Jackson 이 `zoneId` 를 JSON 에 같이 내보낸다 */
    fun zoneId(): ZoneId = ZoneId.of(zone)

    /** 이벤트 시간대 기준 ZonedDateTime */
    fun atEventZone(): ZonedDateTime = at.atZone(zoneId())

    /** 보는 사람 시간대 기준 */
    fun inZone(viewer: ZoneId): ZonedDateTime = at.atZone(viewer)

    /** 시간대 규칙(tzdata) 갱신 후 파생값 재계산 */
    fun recompute(): ZonedMoment = of(local, zoneId())

    companion object {
        fun of(local: LocalDateTime, zone: ZoneId): ZonedMoment =
            ZonedMoment(local, zone.id, ZonedDateTime.ofLocal(local, zone, null).toInstant())

        fun of(local: LocalDateTime, zone: String): ZonedMoment = of(local, ZoneId.of(zone))

        /** Instant 를 특정 시간대의 현지 시각으로 (예: 지금 시각을 KST 마감으로 기록) */
        fun of(at: Instant, zone: ZoneId): ZonedMoment = ZonedMoment(at.atZone(zone).toLocalDateTime(), zone.id, at)
    }
}
