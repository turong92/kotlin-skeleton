package dev.sumin.skeleton.coupon

import java.time.Instant
import java.time.temporal.ChronoUnit

/** 쿠폰 만료 규칙 — 정의의 `validUntil` 과 발급 + `validDays` 중 이른 것. 상태 `EXPIRED` 는 저장하기 전에도 읽을 때 판정한다 */
object CouponExpiry {
    fun expiresAt(definition: CouponDefinition, issuedAt: Instant): Instant? =
        listOfNotNull(definition.validUntil, definition.validDays?.let { issuedAt.plus(it.toLong(), ChronoUnit.DAYS) }).minOrNull()

    /** 발행 기간 안인가 - 시작은 포함, 끝은 제외 */
    fun issuingOpen(definition: CouponDefinition, now: Instant): Boolean =
        (definition.issueStartsAt == null || !now.isBefore(definition.issueStartsAt)) && (definition.issueEndsAt == null || now.isBefore(definition.issueEndsAt))

    /** 사용을 시작했는가 - 시작 시각 이후(그 시각 포함) */
    fun usable(definition: CouponDefinition, now: Instant): Boolean = definition.validFrom == null || !now.isBefore(definition.validFrom)

    fun isExpired(expiresAt: Instant?, now: Instant): Boolean = expiresAt != null && !expiresAt.isAfter(now)

    /** 쓸 수 있던 쿠폰이 기한을 넘겼으면 `EXPIRED`. 주문이 쥔 쿠폰(`RESERVED`)은 그대로 둔다 — 결제가 끝나기를 기다리는 중이다 */
    fun statusAt(coupon: Coupon, now: Instant): CouponStatus =
        if (coupon.status == CouponStatus.AVAILABLE && isExpired(coupon.expiresAt, now)) CouponStatus.EXPIRED else coupon.status
}
