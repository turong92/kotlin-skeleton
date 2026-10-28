package dev.sumin.skeleton.common.discount

/** 할인 금액 계산 — 금액은 모두 최소 단위, 버림이고 0 미만 · 금액 초과가 없다 */
object DiscountMath {
    /**
     * [stage] 가 [amount] 에서 깎는 금액. 정률은 버림 후 [DiscountStage.maxDiscount] 로 자르고, 어떤 경우도 [amount] 를 넘지 않는다.
     * [currencyScale] 은 통화의 소수 자리 수 — 금액이 이미 최소 단위라 계산에는 쓰지 않는다(호출 모양을 통화 정보와 함께 고정해 둔다).
     */
    @Suppress("UNUSED_PARAMETER")
    fun discount(amount: Long, stage: DiscountStage, currencyScale: Int): Long {
        require(amount >= 0) { "amount is not negative" }
        val raw = when (stage.type) {
            DiscountStageType.FREE -> amount
            DiscountStageType.FIXED -> stage.value
            // amount * value / 100 의 버림 — 곱이 넘치지 않게 몫과 나머지로 나눈다
            DiscountStageType.PERCENT -> amount / 100 * stage.value + amount % 100 * stage.value / 100
        }
        val capped = if (stage.type == DiscountStageType.PERCENT && stage.maxDiscount != null) minOf(raw, stage.maxDiscount) else raw
        return minOf(capped, amount)
    }

    /** 0 부터 센 회차 [index] 에 걸리는 단계. 단계들이 끝났으면 null (마지막 단계의 periods 가 null 이면 끝까지) */
    fun stageAt(stages: List<DiscountStage>, index: Int): DiscountStage? {
        require(index >= 0) { "index is not negative" }
        var start = 0L
        for (stage in stages) {
            val periods = stage.periods ?: return stage
            start += periods
            if (index < start) return stage
        }
        return null
    }
}
