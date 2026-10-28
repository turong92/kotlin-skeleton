package dev.sumin.skeleton.coupon

import dev.sumin.skeleton.common.discount.DiscountPurpose
import dev.sumin.skeleton.common.discount.DiscountStage
import java.time.Instant

/** 정의 상태 — `ACTIVE` 만 새로 발급된다. 이미 발급된 쿠폰은 정의가 멈추거나 보관돼도 쓸 수 있다 */
enum class CouponDefinitionStatus { ACTIVE, PAUSED, ARCHIVED }

/** 쿠폰 한 장의 상태 — `AVAILABLE → RESERVED(주문) → USED`, 주문이 안 되면 `AVAILABLE` 로 돌아간다. 만료는 [CouponExpiry] 가 읽을 때 판정한다 */
enum class CouponStatus { AVAILABLE, RESERVED, USED, EXPIRED, REVOKED }

/** 쿠폰이 어디서 왔나 — 지급 건(`GRANT`) · 공용 코드(`CODE`) · 운영자(`ADMIN`) */
enum class CouponSource { GRANT, CODE, ADMIN }

/** 쓸 수 있는 범위 — 빈 목록은 전부. [itemRefs] 는 서버끼리의 좁히기용이다(사용자 응답에는 싣지 않는다) */
data class CouponScope(
    val purposes: List<DiscountPurpose> = emptyList(),
    val itemTypes: List<String> = emptyList(),
    val itemRefs: List<String> = emptyList(),
    val planCodes: List<String> = emptyList(),
)

/** 쿠폰 정의 — 마케팅이 행 하나로 만드는 조합. [stages] 는 순서대로 이어지는 혜택 단계(5개까지, 영원은 마지막만) */
data class CouponDefinition(
    val id: String,
    val name: String,
    val status: CouponDefinitionStatus,
    val stages: List<DiscountStage>,
    val currency: String?,
    val minAmount: Long?,
    val scope: CouponScope,
    val firstPurchaseOnly: Boolean,
    val validDays: Int?,
    val validUntil: Instant?,
    val issueLimit: Int?,
    val issuedCount: Int,
    val perAccountLimit: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** 발행 기간 `[issueStartsAt, issueEndsAt)` - 밖에서는 운영자 발급 · 코드 등록 · 보상 지급이 거절된다. null 은 그쪽이 열려 있다 */
    val issueStartsAt: Instant? = null,
    val issueEndsAt: Instant? = null,
    /** 사용 시작 - 이 시각 전에는 결제 할인으로 쓸 수 없다(쿠폰함에는 보인다). null 이면 받자마자 쓴다 */
    val validFrom: Instant? = null,
)

/** 계정이 가진 쿠폰 한 장. [holderRef] 는 마지막으로 쥔 주문 id — 풀려도 지우지 않는다(늦은 결제 확정이 주인을 찾는다) */
data class Coupon(
    val id: String,
    val definitionId: String,
    val accountId: String,
    val status: CouponStatus,
    val holderRef: String?,
    val sourceType: CouponSource,
    val sourceRef: String,
    val expiresAt: Instant?,
    val usedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** 공용 코드 — [code] 는 대문자로 맞춘 값 */
data class CouponCodeRecord(
    val code: String,
    val definitionId: String,
    val maxRedemptions: Int?,
    val redeemedCount: Int,
    val expiresAt: Instant?,
    val createdAt: Instant,
)

/** 쿠폰 + 정의 + 지금 시각으로 읽은 상태 — 응답과 목록이 쓴다 */
data class CouponView(val coupon: Coupon, val definition: CouponDefinition, val status: CouponStatus)

/** 한 정의의 쿠폰 현황 - 상태는 지금 시각으로 읽은 것이다(기한이 지난 `AVAILABLE` 은 [expired]). [discountTotals] 는 확정된(`USED`) 쿠폰이 실제로 깎은 금액을 통화별로 */
data class CouponStats(
    val issued: Int,
    val available: Int,
    val reserved: Int,
    val used: Int,
    val expired: Int,
    val revoked: Int,
    val discountTotals: List<CouponMoney>,
)

data class CouponMoney(val currency: String, val amount: Long)

/** 목록 탭 — `AVAILABLE` 은 쓸 수 있는 것(주문이 쥔 것 포함), `PAST` 는 쓴 · 끝난 것 */
enum class CouponTab { AVAILABLE, PAST }
