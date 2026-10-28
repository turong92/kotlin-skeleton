package dev.sumin.skeleton.coupon.web

import dev.sumin.skeleton.common.discount.DiscountStageType
import dev.sumin.skeleton.coupon.CouponDefinition
import dev.sumin.skeleton.coupon.CouponDefinitionStatus
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
)

data class IssueResponse(val issued: List<String>, val skipped: List<String>)

data class CouponCodeResponse(val code: String)

private fun CouponDefinition.stageResponses() = stages.map { StageResponse(it.type, it.value, it.maxDiscount, it.periods) }

private fun CouponDefinition.scopeResponse() = ScopeResponse(scope.purposes.map { it.name }, scope.itemTypes, scope.planCodes)

fun CouponView.toResponse() = CouponResponse(
    coupon.id, definition.name, status, coupon.expiresAt, definition.stageResponses(), definition.scopeResponse(), definition.currency, definition.minAmount,
)

fun CouponView.toAdminResponse() = AdminCouponResponse(
    coupon.id, definition.name, status, coupon.expiresAt, definition.stageResponses(), definition.scopeResponse(), definition.currency, definition.minAmount,
    coupon.accountId, coupon.definitionId,
)

fun CouponDefinition.toResponse() = CouponDefinitionResponse(
    id, name, status, stageResponses(), currency, minAmount, scopeResponse(), firstPurchaseOnly, validDays, validUntil, issueLimit, perAccountLimit, issuedCount,
)
