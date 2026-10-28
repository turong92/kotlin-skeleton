package dev.sumin.skeleton.coupon.web

import dev.sumin.skeleton.common.discount.DiscountStageType
import dev.sumin.skeleton.coupon.CouponDefinition
import dev.sumin.skeleton.coupon.CouponDefinitionStatus
import dev.sumin.skeleton.coupon.CouponStats
import dev.sumin.skeleton.coupon.CouponStatus
import dev.sumin.skeleton.coupon.CouponView
import java.time.Instant

data class StageResponse(val type: DiscountStageType, val value: Long, val maxDiscount: Long?, val periods: Int?)

data class ScopeResponse(val purposes: List<String>, val itemTypes: List<String>, val planCodes: List<String>)

/** 쿠폰함의 한 장 (C-R) — 이름 · 단계 · 범위는 발급 때의 정의에서 읽는다 */
data class CouponResponse(
    val id: String,
    val name: String,
    val status: CouponStatus,
    val expiresAt: Instant?,
    val stages: List<StageResponse>,
    val scope: ScopeResponse,
    val currency: String?,
    val minAmount: Long?,
    /** 사용 시작 - 이 시각 전에는 결제에 쓸 수 없다(null 이면 바로) */
    val usableFrom: Instant?,
)

/** 운영자가 보는 한 장 — 쿠폰 꼴 + 주인 계정과 정의 */
data class AdminCouponResponse(
    val id: String,
    val name: String,
    val status: CouponStatus,
    val expiresAt: Instant?,
    val stages: List<StageResponse>,
    val scope: ScopeResponse,
    val currency: String?,
    val minAmount: Long?,
    val usableFrom: Instant?,
    val accountId: String,
    val definitionId: String,
)

data class CouponDefinitionResponse(
    val id: String,
    val name: String,
    val status: CouponDefinitionStatus,
    val stages: List<StageResponse>,
    val currency: String?,
    val minAmount: Long?,
    val scope: ScopeResponse,
    val firstPurchaseOnly: Boolean,
    val validDays: Int?,
    val validUntil: Instant?,
    val issueLimit: Int?,
    val perAccountLimit: Int,
    val issuedCount: Int,
    val issueStartsAt: Instant?,
    val issueEndsAt: Instant?,
    val validFrom: Instant?,
)

/** 정의 하나의 발급 내역 한 줄 - [orderRef] 는 쥐고 있거나(`RESERVED`) 쓴(`USED`) 주문 */
data class IssuedCouponResponse(
    val id: String,
    val accountId: String,
    val status: CouponStatus,
    val issuedAt: Instant,
    val expiresAt: Instant?,
    val usableFrom: Instant?,
    val usedAt: Instant?,
    val orderRef: String?,
)

data class MoneyResponse(val currency: String, val amount: Long)

data class CouponStatsResponse(
    val issued: Int,
    val available: Int,
    val reserved: Int,
    val used: Int,
    val expired: Int,
    val revoked: Int,
    val discountTotals: List<MoneyResponse>,
)

data class IssueResponse(val issued: List<String>, val skipped: List<String>)

data class CouponCodeResponse(val code: String)

private fun CouponDefinition.stageResponses() = stages.map { StageResponse(it.type, it.value, it.maxDiscount, it.periods) }

private fun CouponDefinition.scopeResponse() = ScopeResponse(scope.purposes.map { it.name }, scope.itemTypes, scope.planCodes)

fun CouponView.toResponse() = CouponResponse(
    coupon.id, definition.name, status, coupon.expiresAt, definition.stageResponses(), definition.scopeResponse(), definition.currency, definition.minAmount, definition.validFrom,
)

fun CouponView.toAdminResponse() = AdminCouponResponse(
    coupon.id, definition.name, status, coupon.expiresAt, definition.stageResponses(), definition.scopeResponse(), definition.currency, definition.minAmount, definition.validFrom,
    coupon.accountId, coupon.definitionId,
)

fun CouponDefinition.toResponse() = CouponDefinitionResponse(
    id, name, status, stageResponses(), currency, minAmount, scopeResponse(), firstPurchaseOnly, validDays, validUntil, issueLimit, perAccountLimit, issuedCount, issueStartsAt, issueEndsAt, validFrom,
)

fun CouponView.toIssuedResponse() = IssuedCouponResponse(
    coupon.id, coupon.accountId, status, coupon.createdAt, coupon.expiresAt, definition.validFrom, coupon.usedAt,
    coupon.holderRef.takeIf { status == CouponStatus.RESERVED || status == CouponStatus.USED },
)

fun CouponStats.toResponse() = CouponStatsResponse(issued, available, reserved, used, expired, revoked, discountTotals.map { MoneyResponse(it.currency, it.amount) })
