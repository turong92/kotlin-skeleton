package dev.sumin.skeleton.payment

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * 해지·환불 정책 입력(스펙 `2026-10-12-subscription-cancel-refund-design.md`) — 구독 하나와 **이번 기간의** 결제 내역.
 * [paid] 는 이번 기간에 낸 돈 중 아직 돌려받지 않은 몫(여러 주문의 합 — 플랜 올리기 차액 포함), [monthlyPrice] 는 같은 등급의 월 가격(없으면 null),
 * [used] 는 앱의 「썼는가」 답([SubscriptionUsageCheck]).
 */
data class CancelContext(
    val subscription: PaymentSubscription,
    val paid: Long,
    val now: Instant,
    val monthlyPrice: Long?,
    val used: Boolean,
    /** 일할 계산이 날짜를 가르는 시간대 */
    val zone: java.time.ZoneId = Proration.DEFAULT_ZONE,
)

/** 계산 내역 한 줄 — [PAID] 는 양수, [USED] · [PENALTY] 는 음수(합이 환불 금액이다). */
enum class CancelLineCode { PAID, USED, PENALTY }

data class CancelLine(val code: CancelLineCode, val amount: Long)

/** [open] 이 false 면 중도 해지를 열지 않는다(환불 0). [withdrawal] 은 7일 철회로 전액을 돌려주는 경우. */
data class CancelQuote(val open: Boolean, val refund: Long, val lines: List<CancelLine>, val withdrawal: Boolean) {
    companion object {
        val CLOSED = CancelQuote(open = false, refund = 0, lines = emptyList(), withdrawal = false)
    }
}

/** 나라마다 갈아 끼우는 해지·환불 정책 — 구독 하나를 받아 중도 해지를 여는지, 지금 해지하면 얼마를 돌려주는지(내역 포함) 답한다. 계산만 하고 부수 효과가 없다. */
interface SubscriptionCancelPolicy {
    /** 설정 지도(`skeleton.payment.subscription.cancel.policies`)가 가리키는 이름. */
    val name: String

    fun quote(context: CancelContext): CancelQuote
}

/** 스켈레톤 기본 — 기간 끝 해지만. 중도 해지는 닫혀 있고 환불은 없다. */
object PeriodEndOnlyCancelPolicy : SubscriptionCancelPolicy {
    override val name: String = "period-end-only"

    override fun quote(context: CancelContext): CancelQuote = CancelQuote.CLOSED
}

/**
 * 한국 소비자 정책 — 중도 해지가 열려 있고 환불은 `낸 돈 − 쓴 몫 − 위약금`.
 * - 쓴 몫: 월 주기는 일할(남은 일수 비율 - [Proration], 남은 몫을 내림). 월보다 긴 주기(연간 등)는 **시작한 개월 수 × 같은 등급의 월 가격**(할인은 쓴 몫에 안 쳐 준다. 월 가격이 없으면 일할). 낸 돈을 넘지 않는다.
 * - 위약금 = 남은 금액 × [penaltyPercent]% (내림).
 * - 7일 철회: 이번 기간 시작 후 [withdrawalDays] 일 안(그날 끝 시각 포함)이고 앱이 「안 썼다」고 하면 전액, 위약금 없음.
 */
class KoreaConsumerCancelPolicy(private val penaltyPercent: Int = 0, private val withdrawalDays: Long = 7) : SubscriptionCancelPolicy {
    init {
        require(penaltyPercent in 0..100) { "The cancellation penalty percent must be between 0 and 100" }
        require(withdrawalDays >= 0) { "The withdrawal window must not be negative" }
    }

    override val name: String = NAME

    override fun quote(context: CancelContext): CancelQuote {
        val start = context.subscription.currentPeriodStart ?: return CancelQuote.CLOSED
        val end = context.subscription.currentPeriodEnd ?: return CancelQuote.CLOSED
        val paid = context.paid
        if (paid <= 0) return CancelQuote(open = true, refund = 0, lines = emptyList(), withdrawal = false)
        if (!context.used && !context.now.isAfter(start.plus(Duration.ofDays(withdrawalDays)))) {
            return CancelQuote(open = true, refund = paid, lines = listOf(CancelLine(CancelLineCode.PAID, paid)), withdrawal = true)
        }
        val used = usedShare(context, start, end)
        val remaining = paid - used
        val penalty = remaining * penaltyPercent / 100
        val lines = buildList {
            add(CancelLine(CancelLineCode.PAID, paid))
            if (used > 0) add(CancelLine(CancelLineCode.USED, -used))
            if (penalty > 0) add(CancelLine(CancelLineCode.PENALTY, -penalty))
        }
        return CancelQuote(open = true, refund = remaining - penalty, lines = lines, withdrawal = false)
    }

    private fun usedShare(context: CancelContext, start: Instant, end: Instant): Long {
        val paid = context.paid
        val months = SubscriptionPeriod.parse(context.subscription.period).calendarMonths
        if (months != null && months > 1 && context.monthlyPrice != null) {
            return minOf(paid, startedMonths(start, context.now, months) * context.monthlyPrice)
        }
        return paid - Proration.remaining(paid, start, end, context.now, context.zone)
    }

    /** 이미 시작한 개월 수(시작 시각에 첫 달이 시작된다) — 주기의 개월 수를 넘지 않는다. */
    private fun startedMonths(start: Instant, now: Instant, months: Int): Long {
        val from = start.atZone(ZoneOffset.UTC)
        var started = 0L
        while (started < months && !from.plusMonths(started).toInstant().isAfter(now)) started++
        return started
    }

    companion object {
        const val NAME = "korea"
    }
}

/** 이번 기간의 구독을 **썼는가** — 앱이 구현한다(7일 철회 판단에 쓰인다). 기본은 [NEVER_USED] 라서 7일 안이면 안 쓴 것으로 본다. */
fun interface SubscriptionUsageCheck {
    /** [since] 는 이번 기간의 시작. 짧게 끝낸다 — 락 밖에서 불린다. */
    fun used(subscription: PaymentSubscription, since: Instant, now: Instant): Boolean

    companion object {
        val NEVER_USED: SubscriptionUsageCheck = SubscriptionUsageCheck { _, _, _ -> false }
    }
}

/**
 * 나라 → 정책 지도. [countryToPolicy] 는 설정(`skeleton.payment.subscription.cancel.policies`)이고 지도에 없는 나라(나라가 비어도)는 [PeriodEndOnlyCancelPolicy] 다.
 * 지도가 모르는 정책 이름을 가리키면 만들 때 던진다(기동 실패).
 */
class SubscriptionCancelPolicies(policies: List<SubscriptionCancelPolicy>, private val countryToPolicy: Map<String, String>) {
    private val byName: Map<String, SubscriptionCancelPolicy> = (listOf(PeriodEndOnlyCancelPolicy) + policies).associateBy { it.name }

    init {
        countryToPolicy.forEach { (country, name) ->
            require(name in byName) { "skeleton.payment.subscription.cancel.policies.$country points at the unknown policy '$name' (known: ${byName.keys.sorted()})" }
        }
    }

    fun forCountry(country: String?): SubscriptionCancelPolicy = byName.getValue(countryToPolicy[country?.uppercase()] ?: PeriodEndOnlyCancelPolicy.name)
}
