package dev.sumin.skeleton.payment

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 일할 계산 한 곳 - 플랜 변경 차액([PlanChangePricing])과 중도 해지의 쓴 몫([KoreaConsumerCancelPolicy])이 같은 규칙을 쓴다.
 * 규칙: **날짜(일) 단위**. 기간 일수 = 끝 날짜 - 시작 날짜, 남은 일수 = 끝 날짜 - 오늘 날짜(오늘은 쓴 날로 친다), 날짜는 [zone](앱 시간대) 기준. 금액은 내림.
 * 기간이 하루보다 짧으면(시험용 `PT3M` 같은 주기) 시간(초) 비율로 계산한다.
 */
object Proration {
    val DEFAULT_ZONE: ZoneId = ZoneId.of("Asia/Seoul")

    /** 남은 몫 - (남은, 전체) 단위는 일(하루 미만 기간이면 초). 남은 값은 0..전체. */
    fun share(start: Instant, end: Instant, now: Instant, zone: ZoneId = DEFAULT_ZONE): Pair<Long, Long> {
        if (Duration.between(start, end) < Duration.ofDays(1)) {
            val total = Duration.between(start, end).seconds
            return Duration.between(now, end).seconds.coerceIn(0, maxOf(total, 0)) to total
        }
        val from = start.atZone(zone).toLocalDate()
        val to = end.atZone(zone).toLocalDate()
        val total = ChronoUnit.DAYS.between(from, to)
        return ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), to).coerceIn(0, total) to total
    }

    /** [amount] 의 남은 몫을 원 단위로 내린 값. 기간이 비었으면 0. */
    fun remaining(amount: Long, start: Instant, end: Instant, now: Instant, zone: ZoneId = DEFAULT_ZONE): Long {
        val (left, total) = share(start, end, now, zone)
        return if (total <= 0) 0 else Math.multiplyExact(amount, left) / total
    }
}
