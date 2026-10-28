package dev.sumin.skeleton.coupon

import dev.sumin.skeleton.common.discount.DiscountPurpose
import dev.sumin.skeleton.common.discount.DiscountStage
import dev.sumin.skeleton.common.discount.DiscountStageType
import dev.sumin.skeleton.common.id.SortableIds
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Instant

/** 단계 입력 — 검증 전의 날값이다. 모양이 틀리면 [CouponDefinitions.create] 가 `COUPON.DEFINITION_INVALID` 로 거절한다 */
data class CouponStageInput(val type: String?, val value: Long? = null, val maxDiscount: Long? = null, val periods: Int? = null)

data class CouponScopeInput(
    val purposes: List<String> = emptyList(),
    val itemTypes: List<String> = emptyList(),
    val itemRefs: List<String> = emptyList(),
    val planCodes: List<String> = emptyList(),
)

data class CouponDefinitionInput(
    val name: String?,
    val stages: List<CouponStageInput>,
    val currency: String? = null,
    val minAmount: Long? = null,
    val scope: CouponScopeInput = CouponScopeInput(),
    val firstPurchaseOnly: Boolean = false,
    val validDays: Int? = null,
    val validUntil: Instant? = null,
    val issueLimit: Int? = null,
    val perAccountLimit: Int = 1,
    val issueStartsAt: Instant? = null,
    val issueEndsAt: Instant? = null,
    val validFrom: Instant? = null,
)

/**
 * 쿠폰 정의 만들기 · 읽기 · 멈추기. 정의는 한 번 만들면 내용을 고치지 않는다(상태만) — 진행 중인 쿠폰과 구독이 쓰던 단계가 바뀌지 않게.
 * 단계는 5개까지, `periods` 가 `null`(영원)인 단계는 마지막만, 정률은 1~100, 정액은 통화가 있어야 한다.
 */
class CouponDefinitions(
    private val store: CouponStore,
    private val time: TimeProvider,
    private val ids: SortableIds = SortableIds.system,
) {
    fun create(input: CouponDefinitionInput): CouponDefinition {
        val now = time.now()
        val definition = validated(input, now)
        store.inTransaction { store.insertDefinition(definition) }
        return definition
    }

    fun find(id: String): CouponDefinition? = store.findDefinition(id)

    fun get(id: String): CouponDefinition = find(id) ?: throw CouponException(CouponErrorCode.DEFINITION_NOT_FOUND, "coupon definition $id not found")

    fun list(limit: Int = MAX_LIST): List<CouponDefinition> = store.listDefinitions(limit.coerceIn(1, MAX_LIST))

    fun setStatus(id: String, status: CouponDefinitionStatus): CouponDefinition {
        store.inTransaction {
            if (!store.updateDefinitionStatus(id, status, time.now())) get(id)   // 이미 그 상태면 그대로 — 없을 때만 404
        }
        return get(id)
    }

    private fun validated(input: CouponDefinitionInput, now: Instant): CouponDefinition {
        val name = input.name?.trim().orEmpty()
        invalid(name.isEmpty() || name.length > MAX_NAME, "name is required, up to $MAX_NAME characters")
        val currency = input.currency?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        invalid(currency != null && !CURRENCY.matches(currency), "currency is a 3-letter code")

        val stages = stagesOf(input.stages, currency)
        invalid(input.minAmount != null && input.minAmount < 0, "minAmount is not negative")
        invalid(input.minAmount != null && currency == null, "minAmount needs a currency")
        invalid(input.validDays != null && input.validDays < 1, "validDays is at least 1")
        invalid(input.issueLimit != null && input.issueLimit < 1, "issueLimit is at least 1")
        invalid(input.perAccountLimit < 1, "perAccountLimit is at least 1")
        invalid(input.issueStartsAt != null && input.issueEndsAt != null && !input.issueStartsAt.isBefore(input.issueEndsAt), "issueStartsAt must be before issueEndsAt")
        // 받은 날부터 N일 만료인데 가장 늦게 받아도(발행 끝) 쓸 수 있게 되기 전에 끝난다면 어떤 쿠폰도 못 쓴다
        invalid(
            input.validFrom != null && input.issueEndsAt != null && input.validDays != null &&
                !input.issueEndsAt.plus(input.validDays.toLong(), java.time.temporal.ChronoUnit.DAYS).isAfter(input.validFrom),
            "every coupon would expire (issueEndsAt + validDays) before validFrom",
        )
        invalid(input.validFrom != null && input.validUntil != null && !input.validFrom.isBefore(input.validUntil), "validFrom must be before validUntil")

        return CouponDefinition(
            id = ids.newId(ID_PREFIX),
            name = name,
            status = CouponDefinitionStatus.ACTIVE,
            stages = stages,
            currency = currency,
            minAmount = input.minAmount,
            scope = scopeOf(input.scope),
            firstPurchaseOnly = input.firstPurchaseOnly,
            validDays = input.validDays,
            validUntil = input.validUntil,
            issueLimit = input.issueLimit,
            issuedCount = 0,
            perAccountLimit = input.perAccountLimit,
            createdAt = now,
            updatedAt = now,
            issueStartsAt = input.issueStartsAt,
            issueEndsAt = input.issueEndsAt,
            validFrom = input.validFrom,
        )
    }

    private fun stagesOf(inputs: List<CouponStageInput>, currency: String?): List<DiscountStage> {
        invalid(inputs.isEmpty(), "at least one stage is required")
        invalid(inputs.size > MAX_STAGES, "at most $MAX_STAGES stages")
        return inputs.mapIndexed { index, stage ->
            val type = DiscountStageType.entries.firstOrNull { it.name == stage.type?.trim()?.uppercase() }
                ?: throw invalid("stage ${index + 1}: type is FREE, PERCENT or FIXED")
            val value = stage.value ?: 0
            when (type) {
                DiscountStageType.FREE -> Unit
                DiscountStageType.PERCENT -> invalid(value !in 1..100, "stage ${index + 1}: a percent is 1..100")
                DiscountStageType.FIXED -> {
                    invalid(value < 1, "stage ${index + 1}: a fixed amount is positive")
                    invalid(currency == null, "stage ${index + 1}: a fixed amount needs a currency")
                }
            }
            invalid(stage.maxDiscount != null && (type != DiscountStageType.PERCENT || stage.maxDiscount < 0), "stage ${index + 1}: maxDiscount belongs to a percent stage and is not negative")
            invalid(stage.periods != null && stage.periods < 1, "stage ${index + 1}: periods is at least 1")
            invalid(stage.periods == null && index != inputs.lastIndex, "stage ${index + 1}: only the last stage can run forever (periods null)")
            DiscountStage(type, if (type == DiscountStageType.FREE) 0 else value, stage.maxDiscount, stage.periods)
        }
    }

    private fun scopeOf(input: CouponScopeInput): CouponScope {
        val purposes = input.purposes.map { raw ->
            DiscountPurpose.entries.firstOrNull { it.name == raw.trim().uppercase() } ?: throw invalid("scope: unknown purpose '$raw'")
        }.distinct()
        fun clean(values: List<String>) = values.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return CouponScope(purposes, clean(input.itemTypes), clean(input.itemRefs), clean(input.planCodes))
    }

    private fun invalid(failed: Boolean, message: String) {
        if (failed) throw invalid(message)
    }

    private fun invalid(message: String) = CouponException(CouponErrorCode.DEFINITION_INVALID, message)

    companion object {
        const val ID_PREFIX = "cpd_"
        const val MAX_STAGES = 5
        const val MAX_NAME = 100
        const val MAX_LIST = 200
        private val CURRENCY = Regex("[A-Z]{3}")
    }
}
