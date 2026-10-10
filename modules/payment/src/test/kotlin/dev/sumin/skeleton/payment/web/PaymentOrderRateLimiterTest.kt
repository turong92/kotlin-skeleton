package dev.sumin.skeleton.payment.web

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.web.InMemoryFixedWindowRateLimitStore
import dev.sumin.skeleton.payment.PaymentProperties
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** 주문 만들기 한도의 판정 순서 — 계정이 먼저, 거절이면 IP 통을 깎지 않는다. */
class PaymentOrderRateLimiterTest {
    private val store = InMemoryFixedWindowRateLimitStore()
    private val limiter = PaymentOrderRateLimiter(
        { store },
        TimeProvider.fixed(Instant.parse("2026-10-09T00:00:00Z")),
        PaymentProperties.Http.OrderRateLimit(accountCapacity = 2, ipCapacity = 5, window = Duration.ofMinutes(10)),
    )

    @Test
    fun `an account that is over its own limit does not use up the IP bucket - another account behind the same IP still gets the rest of it`() {
        repeat(2) { assertNull(limiter.acquire("acc_loud", "ip_nat")) }
        repeat(20) { assertNotNull(limiter.acquire("acc_loud", "ip_nat"), "the loud account is over its own limit") }

        // IP 통 5 중 소비된 것은 허락된 2 뿐이어야 한다 — 거절된 20번이 깎았다면 다른 사용자가 한 번도 못 만든다
        repeat(2) { n -> assertNull(limiter.acquire("acc_quiet_$n", "ip_nat"), "quiet account $n behind the same IP") }
        assertNull(limiter.acquire("acc_quiet_2", "ip_nat"))
        assertNotNull(limiter.acquire("acc_quiet_3", "ip_nat"), "the IP bucket is really 5: 2 + 3 used, the next is denied")
    }
}
