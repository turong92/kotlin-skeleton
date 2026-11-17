package dev.sumin.skeleton.common.time

import java.time.Instant
import java.time.ZoneId

/** 「하루」 — 어떤 시간대의 자정부터 다음 자정 전까지 `[from, to)`. 하루 단위 상한이 같은 경계를 쓰게 한 곳에 둔다 */
data class DayWindow(val from: Instant, val to: Instant) {
    companion object {
        /** [now] 가 든 [zone] 의 하루 */
        fun containing(now: Instant, zone: ZoneId): DayWindow {
            val start = now.atZone(zone).toLocalDate().atStartOfDay(zone)
            return DayWindow(start.toInstant(), start.plusDays(1).toInstant())
        }
    }
}
