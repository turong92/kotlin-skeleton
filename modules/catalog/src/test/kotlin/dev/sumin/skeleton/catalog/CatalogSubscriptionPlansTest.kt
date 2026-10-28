package dev.sumin.skeleton.catalog

import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentItemNotPurchasableException
import dev.sumin.skeleton.payment.PurchaseRequest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 표를 결제 계약으로 읽는 곳: 목록은 지금 가격만, find 는 지난 가격도(구독자 불변), 단건은 지금 가격 · 수량 1 · 같은 itemType 만. */
class CatalogSubscriptionPlansTest {
    private val store = FakeCatalogStore()
    private val time = MutableTime()
    private val admin = CatalogAdmin(store, time, RecordingAudit())
    private val properties = CatalogProperties(defaultLocale = "ko")
    private val plans = CatalogSubscriptionPlans(store, time, properties)
    private val catalog = CatalogPaymentCatalog(store, time, properties)
    private val day = Duration.ofDays(1)

    private fun subscription(code: String = "basic", level: Int = 1) = admin.createProduct("a", ProductInput(code, "SUBSCRIPTION", level, texts = TEXTS)).product

    private fun price(product: String, amount: Long, currency: String = "KRW", period: String? = "P1M", code: String? = null, compareAt: Long? = null, sale: Pair<Long, Long>? = null) =
        admin.addPrice("a", product, PriceInput(code, period, amount, currency, compareAt, sale?.let { time.now().plus(day.multipliedBy(it.first)) }, sale?.let { time.now().plus(day.multipliedBy(it.second)) }))

    @Test
    fun `list is the current price of each selling subscription product in every currency, and nothing else`() {
        subscription()
        price("basic", 3_900, code = "basic")
        price("basic", 39_000, period = "P1Y", code = "basic-yearly")
        price("basic", 300, "USD", code = "basic-usd")
        val old = price("basic", 1_900, period = "P3M", code = "basic-old").also { admin.stopPrice("a", it.code) }
        admin.createProduct("a", ProductInput("sticker", "ONE_TIME", null, texts = TEXTS)).also { price("sticker", 1_000, period = null) }
        subscription("hidden", 9).also { price("hidden", 9_900) }.also { admin.updateProduct("a", "hidden", ProductPatch(selling = false)) }

        val list = plans.list()

        assertEquals(setOf("basic", "basic-yearly", "basic-usd"), list.map { it.code }.toSet())
        assertFalse(old.code in list.map { it.code })
        val basic = list.single { it.code == "basic" }
        assertEquals(listOf("베이직", PaymentAmount(3_900, "KRW"), "P1M", 1, "basic", true, null), listOf(basic.name, basic.amount, basic.period, basic.level, basic.productCode, basic.purchasable, basic.regularAmount))
    }

    @Test
    fun `a sale replaces the evergreen price in the list while it runs, carries the list price, and the evergreen price is not purchasable then`() {
        subscription()
        price("basic", 3_900, code = "basic")
        price("basic", 2_900, code = "basic-sale", compareAt = 3_900, sale = 0L to 2L)

        assertEquals(listOf("basic-sale"), plans.list().map { it.code })
        assertEquals(3_900, plans.list().single().regularAmount)
        assertEquals(false, plans.find("basic")?.purchasable, "the evergreen price is hidden behind the sale - there is no way to buy the list price during it")
        assertEquals(true, plans.find("basic-sale")?.purchasable)

        time.current = time.current.plus(day.multipliedBy(2))
        assertEquals(listOf("basic"), plans.list().map { it.code })
        assertEquals(false, plans.find("basic-sale")?.purchasable, "the end instant itself is already over")
    }

    @Test
    fun `find still answers for a stopped price and a stopped product - not purchasable, same name, level, period and amount`() {
        subscription()
        val monthly = price("basic", 3_900, code = "basic")
        admin.stopPrice("a", monthly.code)
        admin.updateProduct("a", "basic", ProductPatch(selling = false))

        val found = plans.find("basic")!!

        assertFalse(found.purchasable)
        assertEquals(listOf("베이직", 3_900L, "P1M", 1), listOf(found.name, found.amount.amount, found.period, found.level))
        assertEquals(emptyList(), plans.list())
        assertNull(plans.find("missing"))
    }

    @Test
    fun `a one-time price is not a plan, and the plan name falls back to the first language`() {
        admin.createProduct("a", ProductInput("sticker", "ONE_TIME", null, texts = mapOf("en" to ProductTextInput("Sticker"))))
        price("sticker", 1_000, period = null, code = "sticker")
        assertNull(plans.find("sticker"))

        admin.createProduct("a", ProductInput("pro", "SUBSCRIPTION", 3, texts = mapOf("en" to ProductTextInput("Pro"))))
        price("pro", 9_900, code = "pro")
        assertEquals("Pro", plans.find("pro")?.name, "no ko name - the first language")
    }

    // --- 단건 ---

    private fun request(ref: String, type: String = "product", quantity: Int = 1) = PurchaseRequest("acc_1", type, ref, quantity, "KR")

    @Test
    fun `a one-time purchase quotes the current price with the product name and code`() {
        admin.createProduct("a", ProductInput("sticker", "ONE_TIME", null, texts = mapOf("ko" to ProductTextInput("스티커"), "en" to ProductTextInput("Sticker"))))
        price("sticker", 1_000, period = null, code = "sticker-krw")
        price("sticker", 700, period = null, code = "sticker-krw-sale", compareAt = 1_000, sale = 0L to 1L)

        val quote = catalog.quote(request("sticker-krw-sale"))

        assertEquals(PaymentAmount(700, "KRW"), quote.amount)
        assertEquals("스티커", quote.orderName)
        assertEquals("sticker", quote.productCode)
        assertFailsWith<PaymentItemNotPurchasableException> { catalog.quote(request("sticker-krw")) }
    }

    @Test
    fun `another item type, a quantity of two, a subscription price, a stopped price and an unknown code are not purchasable`() {
        admin.createProduct("a", ProductInput("sticker", "ONE_TIME", null, texts = TEXTS))
        val sticker = price("sticker", 1_000, period = null, code = "sticker")
        subscription().also { price("basic", 3_900, code = "basic") }

        assertEquals(PaymentAmount(1_000, "KRW"), catalog.quote(request("sticker")).amount)
        assertFailsWith<PaymentItemNotPurchasableException> { catalog.quote(request("sticker", type = "cheer")) }
        assertFailsWith<PaymentItemNotPurchasableException> { catalog.quote(request("sticker", quantity = 2)) }
        assertFailsWith<PaymentItemNotPurchasableException> { catalog.quote(request("basic")) }
        assertFailsWith<PaymentItemNotPurchasableException> { catalog.quote(request("missing")) }
        admin.stopPrice("a", sticker.code)
        assertFailsWith<PaymentItemNotPurchasableException> { catalog.quote(request("sticker")) }
    }

    @Test
    fun `provider links are read by price code, per provider`() {
        subscription()
        val usd = price("basic", 300, "USD", code = "basic-usd")
        admin.setProviderRef("a", usd.code, "paddle", "pri_usd")
        val refs = CatalogProviderPriceRefs(store)

        assertEquals("pri_usd", refs.priceIdOf("Paddle", "basic-usd"))
        assertNull(refs.priceIdOf("paddle", "basic"))
        assertNull(refs.priceIdOf("toss", "basic-usd"))
        assertEquals(mapOf("basic-usd" to "pri_usd"), refs.all("paddle"))
        assertTrue(refs.all("toss").isEmpty())
    }
}
