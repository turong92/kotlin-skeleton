package dev.sumin.skeleton.payment

import java.time.Instant

/** 갈아타기의 종류 - 판정은 [PlanChangeRules.classify] 한 곳이다. */
enum class PlanChangeKind {
    /** 같은 주기의 윗등급 - 바로, 남은 기간의 차액만(또는 전액 새 주기: 앱 설정) */
    IMMEDIATE_SAME_PERIOD,

    /** 더 긴 주기로(월 → 연, 등급 같거나 위) - 바로, 새 가격 - 이번 기간에 낸 돈의 남은 몫, 오늘부터 새 주기 */
    IMMEDIATE_NEW_PERIOD,

    /** 등급 내리기 · 주기 줄이기(등급↑ + 주기↓ 포함) - 다음 갱신부터 */
    SCHEDULED,

    NOT_ALLOWED,
}

/**
 * 「이 플랜에서 저 플랜으로」를 어떻게 처리하나. 통화가 다르거나 같은 플랜이거나 등급도 주기도 그대로면 거절이다(통화는 바꾸지 않는다).
 * 주기 길이는 같은 기준 시각에서 한 주기 뒤를 비교한다(`P1M` < `P1Y`).
 */
object PlanChangeRules {
    private val ANCHOR: Instant = Instant.parse("2000-01-01T00:00:00Z")

    fun classify(current: SubscriptionPlan, target: SubscriptionPlan, subscription: PaymentSubscription): PlanChangeKind {
        if (target.code == current.code || target.amount.currency != subscription.amount.currency) return PlanChangeKind.NOT_ALLOWED
        val currentEnd = SubscriptionPeriod.parse(subscription.period).after(ANCHOR, 1)
        val targetEnd = SubscriptionPeriod.parse(target.period).after(ANCHOR, 1)
        val periodOrder = targetEnd.compareTo(currentEnd)
        val levelOrder = target.level.compareTo(current.level)
        return when {
            levelOrder < 0 -> PlanChangeKind.SCHEDULED
            periodOrder < 0 -> PlanChangeKind.SCHEDULED
            periodOrder > 0 -> PlanChangeKind.IMMEDIATE_NEW_PERIOD
            levelOrder > 0 -> PlanChangeKind.IMMEDIATE_SAME_PERIOD
            else -> PlanChangeKind.NOT_ALLOWED
        }
    }
}
