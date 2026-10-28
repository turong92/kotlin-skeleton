package dev.sumin.skeleton.common.discount

/** 할인 한 단계의 모양 */
enum class DiscountStageType { FREE, PERCENT, FIXED }

/**
 * 할인 단계. [value] 는 PERCENT 면 1~100, FIXED 면 최소 단위 금액, FREE 면 쓰지 않는다. [maxDiscount] 는 PERCENT 의 상한(최소 단위).
 * [periods] 는 이 단계가 몇 회차 이어지는지 — null 이면 끝까지(마지막 단계만 뜻이 있다).
 */
data class DiscountStage(val type: DiscountStageType, val value: Long = 0, val maxDiscount: Long? = null, val periods: Int? = null) {
    init {
        when (type) {
            DiscountStageType.FREE -> Unit
            DiscountStageType.PERCENT -> require(value in 1..100) { "a percent discount is 1..100" }
            DiscountStageType.FIXED -> require(value >= 0) { "a fixed discount is not negative" }
        }
        require(maxDiscount == null || maxDiscount >= 0) { "maxDiscount is not negative" }
        require(periods == null || periods >= 1) { "periods is at least 1" }
    }
}

enum class DiscountPurpose { ONE_TIME, SUBSCRIPTION }

/** 할인을 얹을 결제 대상 */
data class DiscountTarget(
    val accountId: String,
    val itemType: String,
    val itemRef: String,
    val planCode: String?,
    val amount: Long,
    val currency: String,
    val purpose: DiscountPurpose,
    val firstPurchase: Boolean,
)

/** 주문이 가져온 할인 수단 지정 — [kind] 는 [DiscountSource.kind], [ref] 는 그 수단 안의 식별(쿠폰 id · 코드) */
data class DiscountClaim(val kind: String, val ref: String)

/** 할인 수단이 내놓는 제안 — [sourceRef] 는 확정 · 복원이 쓰는 원천 식별(쿠폰 id), [stages] 는 단계 */
data class DiscountOffer(val sourceRef: String, val label: String, val stages: List<DiscountStage>)

enum class DiscountConfirmOutcome { CONFIRMED, ALREADY, CONFLICT }

enum class DiscountRejection { NOT_APPLICABLE, UNAVAILABLE }

class DiscountRejectedException(val reason: DiscountRejection, message: String? = null) : RuntimeException(message ?: reason.name)

/**
 * 결제 쪽 할인 수단 공통 틀 — 지금 쿠폰, 다음 판 포인트. `payment` 가 [order] 순으로 [quote] → [reserve] → [confirm] / [release] / [restore] 를 부른다.
 * 결제 면제는 할인이 아니다(`payment` 안의 따로 된 길).
 */
interface DiscountSource {
    val kind: String
    val order: Int

    /** 쓸 수 있는지 보고 제안만 낸다 — 아무것도 잡지 않는다. 못 쓰면 [DiscountRejectedException] */
    fun quote(claim: DiscountClaim, target: DiscountTarget): DiscountOffer

    /** [holderRef](주문 id)가 쓰려고 잡는다 — 같은 건을 둘이 잡을 수 없다 */
    fun reserve(claim: DiscountClaim, target: DiscountTarget, holderRef: String): DiscountOffer

    /** 결제가 끝났다 — 잡은 것을 쓴 것으로 굳힌다 */
    fun confirm(holderRef: String): DiscountConfirmOutcome

    /** 결제가 안 됐다 — 잡은 것을 푼다 */
    fun release(holderRef: String)

    /** 쓴 것을 되돌린다(전액 환불 · 구독 첫 주문 철회) */
    fun restore(holderRef: String)
}

/** 통화별 금액 - [amount] 는 통화 최소 단위 */
data class CurrencyAmount(val currency: String, val amount: Long)

/**
 * 할인 수단이 실제로 깎아 준 금액을 묻는 읽기 전용 조회 - 할인액은 결제 장부(`payment`)에만 있으므로 수단 모듈(`coupon`)이 장부의 표를 모르고도 현황을 만들 수 있게 한다.
 * `payment-jdbc` 가 낸다; 없는 조립에서는 이 빈이 없고 묻는 쪽이 「모른다」(빈 목록)로 읽는다.
 */
fun interface DiscountTotals {
    /** 할인 수단 [kind] 의 [sourceRefs](수단 안의 식별 - 쿠폰 id)에 대해 **확정되어 아직 되돌려지지 않은** 할인액의 합을 통화별로(통화 순). 전액 환불로 복원된 줄은 뺀다 */
    fun confirmedTotals(kind: String, sourceRefs: Collection<String>): List<CurrencyAmount>
}
