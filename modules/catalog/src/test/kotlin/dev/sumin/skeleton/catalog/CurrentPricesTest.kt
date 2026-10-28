package dev.sumin.skeleton.catalog

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 「지금 살 수 있는 가격」 - 열쇠(주기, 통화)마다 하나, 행사 우선, 끝은 열린 구간. 순수 규칙이라 저장소 없이 돈다. */
class CurrentPricesTest {
    private val t0 = Instant.parse("2026-10-29T00:00:00Z")
    private val hour = Duration.ofHours(1)

    private fun price(
        code: String,
        amount: Long = 3_900,
        currency: String = "KRW",
        period: String? = "P1M",
        starts: Instant? = null,
        ends: Instant? = null,
        selling: Boolean = true,
        compareAt: Long? = null,
    ) = CatalogPrice("prc_$code", "prd_1", code, period, amount, currency, compareAt, starts, ends, selling, null, t0, t0)

    private fun codes(prices: List<CatalogPrice>, now: Instant) = CurrentPrices.current(prices, now).map { it.code }

    @Test
    fun `an evergreen price alone is the current price`() {
        assertEquals(listOf("basic"), codes(listOf(price("basic")), t0))
    }

    @Test
    fun `a sale is current from its start instant to the last second before its end, and the evergreen price comes back exactly at the end`() {
        val prices = listOf(price("basic"), price("basic-sale", 2_900, starts = t0.plus(hour), ends = t0.plus(hour.multipliedBy(3)), compareAt = 3_900))

        assertEquals(listOf("basic"), codes(prices, t0.plus(hour).minusSeconds(1)), "one second before the start")
        assertEquals(listOf("basic-sale"), codes(prices, t0.plus(hour)), "at the start instant")
        assertEquals(listOf("basic-sale"), codes(prices, t0.plus(hour.multipliedBy(3)).minusSeconds(1)), "one second before the end")
        assertEquals(listOf("basic"), codes(prices, t0.plus(hour.multipliedBy(3))), "at the end instant the sale is over (open interval)")
    }

    @Test
    fun `a stopped price is never current, and without any evergreen price a gap has no current price`() {
        assertEquals(emptyList(), codes(listOf(price("basic", selling = false)), t0))
        val onlySale = listOf(price("sale", starts = t0.plus(hour), ends = t0.plus(hour.multipliedBy(2))))
        assertEquals(emptyList(), codes(onlySale, t0))
    }

    @Test
    fun `different periods and currencies are different keys - a KRW sale does not replace the USD current price`() {
        val prices = listOf(
            price("basic"), price("basic-yearly", 39_000, period = "P1Y"), price("basic-usd", 300, "USD"),
            price("basic-krw-sale", 2_900, starts = t0, ends = t0.plus(hour), compareAt = 3_900),
        )

        assertEquals(setOf("basic-krw-sale", "basic-yearly", "basic-usd"), codes(prices, t0).toSet())
    }

    @Test
    fun `the later started sale wins when two overlap by accident`() {
        val prices = listOf(price("a", starts = t0, ends = t0.plus(hour.multipliedBy(5))), price("b", starts = t0.plus(hour), ends = t0.plus(hour.multipliedBy(5))))

        assertEquals(listOf("b"), codes(prices, t0.plus(hour)))
    }

    @Test
    fun `two evergreen prices of one key overlap, a sale and an evergreen price do not, and sales overlap only when their periods do`() {
        val evergreen = price("basic")
        assertTrue(CurrentPrices.overlaps(listOf(evergreen), price("again")))
        assertFalse(CurrentPrices.overlaps(listOf(evergreen), price("sale", starts = t0, ends = t0.plus(hour))))

        val sale = price("sale", starts = t0.plus(hour), ends = t0.plus(hour.multipliedBy(3)))
        assertTrue(CurrentPrices.overlaps(listOf(sale), price("inside", starts = t0.plus(hour.multipliedBy(2)), ends = t0.plus(hour.multipliedBy(4)))))
        assertFalse(CurrentPrices.overlaps(listOf(sale), price("after", starts = t0.plus(hour.multipliedBy(3)), ends = t0.plus(hour.multipliedBy(4)))), "back to back is fine")
        assertFalse(CurrentPrices.overlaps(listOf(sale), price("before", starts = t0, ends = t0.plus(hour))))
        assertTrue(CurrentPrices.overlaps(listOf(sale), price("open ended", starts = t0.plus(hour.multipliedBy(2)))), "no end = forever")
    }

    @Test
    fun `other keys, stopped prices and the price itself are not compared`() {
        val sale = price("sale", starts = t0, ends = t0.plus(hour))
        assertFalse(CurrentPrices.overlaps(listOf(sale), price("usd", 300, "USD", starts = t0, ends = t0.plus(hour))))
        assertFalse(CurrentPrices.overlaps(listOf(sale), price("yearly", period = "P1Y", starts = t0, ends = t0.plus(hour))))
        assertFalse(CurrentPrices.overlaps(listOf(price("old", starts = t0, ends = t0.plus(hour), selling = false)), price("new", starts = t0, ends = t0.plus(hour))))
        assertFalse(CurrentPrices.overlaps(listOf(sale), sale.copy(saleEndsAt = t0.plus(hour.multipliedBy(9)))), "the same code is the price being changed")
    }
}
