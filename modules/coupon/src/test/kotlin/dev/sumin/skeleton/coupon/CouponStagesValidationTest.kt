package dev.sumin.skeleton.coupon

import dev.sumin.skeleton.common.discount.DiscountStageType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 마케팅이 넣는 단계 목록의 모양 검사 - 틀린 정의는 하나도 저장되지 않고 400 `COUPON.DEFINITION_INVALID` */
class CouponStagesValidationTest {
    private val store = FakeCouponStore()
    private val definitions = CouponDefinitions(store, MutableTime())

    private fun create(stages: List<CouponStageInput>, currency: String? = "KRW", minAmount: Long? = null) =
        definitions.create(CouponDefinitionInput(name = "test", stages = stages, currency = currency, minAmount = minAmount))

    private fun assertInvalid(block: () -> Unit) {
        val e = assertFailsWith<CouponException> { block() }
        assertEquals("COUPON.DEFINITION_INVALID", e.errorCode.code)
        assertEquals(400, e.status.value())
        assertEquals(0, store.definitions.size)
    }

    private fun stage(type: String, value: Long? = null, periods: Int? = 1, max: Long? = null) = CouponStageInput(type, value, max, periods)

    @Test
    fun `zero stages and six stages are rejected`() {
        assertInvalid { create(emptyList()) }
        assertInvalid { create(List(6) { stage("FREE") }) }
    }

    @Test
    fun `a stage that runs forever must be the last one`() {
        assertInvalid { create(listOf(stage("PERCENT", 10, periods = null), stage("FREE"))) }
    }

    @Test
    fun `a percent is 1 to 100`() {
        assertInvalid { create(listOf(stage("PERCENT", 0))) }
        assertInvalid { create(listOf(stage("PERCENT", 101))) }
    }

    @Test
    fun `a fixed stage needs a currency`() {
        assertInvalid { create(listOf(stage("FIXED", 1000)), currency = null) }
    }

    @Test
    fun `an unknown stage type is rejected`() {
        assertInvalid { create(listOf(stage("BOGO", 1))) }
    }

    @Test
    fun `the combos marketing asks for are valid and keep their order`() {
        val firstFreeThenHalf = create(listOf(stage("FREE", periods = 1), stage("percent", 50, periods = 3)), currency = null)
        assertEquals(listOf(DiscountStageType.FREE, DiscountStageType.PERCENT), firstFreeThenHalf.stages.map { it.type })
        assertEquals(listOf(1, 3), firstFreeThenHalf.stages.map { it.periods })

        val forever = create(listOf(stage("PERCENT", 10, periods = null)), currency = null)
        assertEquals(null, forever.stages.single().periods)

        val fixed = create(listOf(stage("FIXED", 1000)), currency = "krw", minAmount = 5000)
        assertEquals("KRW", fixed.currency)
        assertEquals(CouponDefinitionStatus.ACTIVE, fixed.status)
    }
}
