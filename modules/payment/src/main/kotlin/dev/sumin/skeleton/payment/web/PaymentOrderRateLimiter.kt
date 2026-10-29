package dev.sumin.skeleton.payment.web

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.web.RateLimitDecision
import dev.sumin.skeleton.common.web.RateLimitStore
import dev.sumin.skeleton.payment.PaymentProperties

/**
 * 주문 만들기의 요청 수 한도 — 계정마다 · 호출자 IP 마다, 플랫폼의 [RateLimitStore] 위에(`redis-rate-limit` 이 저장소를 내면 인스턴스끼리 공유, 아니면 이 인스턴스의 메모리).
 * 이유: 주문 하나는 웹훅 조회를 부를 수 있는 창구 하나라서(주문 단위 창 상한 × 주문 수), 계정당 주문 수가 결제사 조회 유발의 전체 상한을 정한다.
 * 넘으면 `429 COMMON.TOO_MANY_REQUESTS` + `Retry-After`.
 */
class PaymentOrderRateLimiter(
    private val store: () -> RateLimitStore,
    private val time: TimeProvider,
    private val limit: PaymentProperties.Http.OrderRateLimit,
) {
    /**
     * 한도 안이면 `null`, 넘으면 기다릴 초. **계정을 먼저 판정하고, 계정이 거절이면 IP 통을 건드리지 않는다** —
     * 한 계정이 되풀이해 두드린 거절 호출이 같은 NAT 뒤 다른 사용자의 IP 통을 비우지 못하게.
     * [bucket] 은 통의 이름이고 [scale] 은 한도의 배수다 - 결제사 API 를 부르는 미리보기는 별도 통에 더 큰 한도를 줘서 미리보기를 여러 번 불러도 정작 바꾸기가 막히지 않게 한다.
     * (IP 가 거절일 때는 계정 통이 이미 한 번 깎인 뒤다 — 그 계정은 어차피 이 창에서 시도한 것이고, 반대 순서보다 해가 작다.)
     */
    fun acquire(accountId: String, clientKey: String, bucket: String = "payment-order", scale: Int = 1): Long? {
        val now = time.now()
        val windowMillis = limit.window.toMillis()
        val account = store().consume("$bucket:account:$accountId", limit.accountCapacity * scale, windowMillis, now)
        if (!account.allowed) return waitSeconds(account, now)
        val ip = store().consume("$bucket:ip:$clientKey", limit.ipCapacity * scale, windowMillis, now)
        return if (ip.allowed) null else waitSeconds(ip, now)
    }

    private fun waitSeconds(decision: RateLimitDecision, now: java.time.Instant): Long = (decision.resetAt.epochSecond - now.epochSecond).coerceAtLeast(1)
}
