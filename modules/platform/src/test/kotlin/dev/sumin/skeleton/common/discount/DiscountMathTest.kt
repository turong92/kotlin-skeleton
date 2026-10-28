package dev.sumin.skeleton.common.discount

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DiscountMathTest {
    private fun percent(value: Long, max: Long? = null, periods: Int? = null) = DiscountStage(DiscountStageType.PERCENT, value, max, periods)
    private val free = { periods: Int? -> DiscountStage(DiscountStageType.FREE, periods = periods) }

    @Test
    fun `percent rounds down`() = assertEquals(2_999, DiscountMath.discount(9_999, percent(30), 0))

    @Test
    fun `percent is capped by maxDiscount`() = assertEquals(2_000, DiscountMath.discount(9_999, percent(30, max = 2_000), 0))

    @Test
    fun `fixed never goes below zero`() = assertEquals(3_000, DiscountMath.discount(3_000, DiscountStage(DiscountStageType.FIXED, 5_000), 0))

    @Test
    fun `free takes everything`() = assertEquals(7_000, DiscountMath.discount(7_000, free(null), 0))

    @Test
    fun `percent does not overflow on large amounts`() =
        assertEquals(Long.MAX_VALUE / 100 * 99 + (Long.MAX_VALUE % 100) * 99 / 100, DiscountMath.discount(Long.MAX_VALUE, percent(99), 0))

    @Test
    fun `a stage list walks one free period then three at half`() {
        val stages = listOf(free(1), percent(50, periods = 3))
        val types = (0..4).map { DiscountMath.stageAt(stages, it)?.type }
        assertEquals(listOf(DiscountStageType.FREE, DiscountStageType.PERCENT, DiscountStageType.PERCENT, DiscountStageType.PERCENT, null), types)
    }

    @Test
    fun `an open ended last stage covers every later period`() =
        assertEquals(percent(10), DiscountMath.stageAt(listOf(percent(10)), 1000))

    @Test
    fun `an empty list has no stage`() = assertNull(DiscountMath.stageAt(emptyList(), 0))

    @Test
    fun `a percent stage outside 1 to 100 is refused`() {
        assertFailsWith<IllegalArgumentException> { percent(0) }
        assertFailsWith<IllegalArgumentException> { percent(101) }
    }
}
