package dev.sumin.skeleton.catalog

import dev.sumin.skeleton.common.ApplicationException
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 운영 규칙: 코드 · 등급 중복, 상시 하나 · 행사 겹침 거절, 금액을 바꾸는 길 없음, 판매 중지는 되돌리지 않음, 감사 기록. 메모리 저장소. */
class CatalogAdminTest {
    private val store = FakeCatalogStore()
    private val time = MutableTime()
    private val audit = RecordingAudit()
    private val admin = CatalogAdmin(store, time, audit, providers = { listOf("paddle") })
    private val now get() = time.now()
    private val day = Duration.ofDays(1)

    private fun basic(level: Int = 1, code: String = "basic") = admin.createProduct("adm_1", ProductInput(code, "subscription", level, texts = TEXTS)).product

    private fun krw(product: String = "basic", amount: Long = 3_900, period: String? = "P1M", code: String? = null, compareAt: Long? = null, starts: Instant? = null, ends: Instant? = null) =
        admin.addPrice("adm_1", product, PriceInput(code, period, amount, "KRW", compareAt ?: if (starts != null || ends != null) amount + 1_000 else null, starts, ends))

    private fun <T : Throwable> code(error: T) = (error as ApplicationException).errorCode

    @Test
    fun `a product code and a subscription level can be used once`() {
        basic()
        assertEquals(CatalogErrorCode.CODE_TAKEN, code(assertFailsWith<ApplicationException> { basic(level = 2) }))
        assertEquals(CatalogErrorCode.LEVEL_TAKEN, code(assertFailsWith<ApplicationException> { basic(level = 1, code = "standard") }))
        assertEquals(listOf("basic"), store.products.keys.toList(), "the refused ones left nothing")
    }

    @Test
    fun `the shape of a product is checked - kind, level for the kind, a language with a name`() {
        assertFailsWith<ApplicationException> { admin.createProduct("adm_1", ProductInput("Bad Code", "SUBSCRIPTION", 1, texts = TEXTS)) }
        assertFailsWith<ApplicationException> { admin.createProduct("adm_1", ProductInput("x", "MONTHLY", 1, texts = TEXTS)) }
        assertFailsWith<ApplicationException> { admin.createProduct("adm_1", ProductInput("x", "SUBSCRIPTION", null, texts = TEXTS)) }
        assertFailsWith<ApplicationException> { admin.createProduct("adm_1", ProductInput("x", "ONE_TIME", 1, texts = TEXTS)) }
        assertFailsWith<ApplicationException> { admin.createProduct("adm_1", ProductInput("x", "ONE_TIME", null, texts = emptyMap())) }
        assertFailsWith<ApplicationException> { admin.createProduct("adm_1", ProductInput("x", "ONE_TIME", null, texts = mapOf("en" to ProductTextInput(" ")))) }
        assertEquals(emptyMap(), store.products)
    }

    @Test
    fun `a second evergreen price of the same product period and currency is refused, other periods and currencies are fine`() {
        basic()
        krw()
        assertEquals(CatalogErrorCode.PRICE_OVERLAP, code(assertFailsWith<ApplicationException> { krw(amount = 4_900) }))
        krw(amount = 39_000, period = "P1Y")
        admin.addPrice("adm_1", "basic", PriceInput(period = "P1M", amount = 300, currency = "usd"))
        assertEquals(3, store.prices.size)
    }

    @Test
    fun `sales may not overlap each other but may sit on top of the evergreen price`() {
        basic()
        krw()
        krw(amount = 2_900, compareAt = 3_900, starts = now.plus(day), ends = now.plus(day.multipliedBy(3)))
        assertEquals(CatalogErrorCode.PRICE_OVERLAP, code(assertFailsWith<ApplicationException> { krw(amount = 2_500, starts = now.plus(day.multipliedBy(2)), ends = now.plus(day.multipliedBy(4))) }))
        krw(amount = 2_500, starts = now.plus(day.multipliedBy(3)), ends = now.plus(day.multipliedBy(4)))
        assertEquals(3, store.prices.size)
    }

    @Test
    fun `after the old evergreen price is stopped a new one may take its place`() {
        basic()
        val old = krw()
        admin.stopPrice("adm_1", old.code)
        krw(amount = 4_900)
        assertEquals(2, store.prices.size)
    }

    @Test
    fun `price inputs are checked - amount, currency, period for the kind, list price above the amount, start before end, an end that is not past`() {
        basic()
        admin.createProduct("adm_1", ProductInput("sticker", "ONE_TIME", null, texts = TEXTS))
        fun bad(block: () -> Unit) = assertEquals(CatalogErrorCode.PRICE_INVALID, code(assertFailsWith<ApplicationException> { block() }))

        bad { krw(amount = 0) }
        bad { admin.addPrice("adm_1", "basic", PriceInput(period = "P1M", amount = 100, currency = "KR")) }
        bad { krw(period = null) }
        bad { krw(period = "monthly") }
        bad { admin.addPrice("adm_1", "sticker", PriceInput(period = "P1M", amount = 1_000, currency = "KRW")) }
        bad { krw(compareAt = 3_900) }
        bad { krw(compareAt = 3_000) }
        bad { krw(starts = now.plus(day), ends = now.plus(day)) }
        bad { krw(starts = now.minus(day.multipliedBy(2)), ends = now.minus(day)) }
        assertEquals(emptyMap(), store.prices)
    }

    @Test
    fun `a missing code is made up from the product, period and currency and never collides`() {
        basic()
        val first = krw()
        val sale = krw(amount = 2_900, starts = now.plus(day), ends = now.plus(day.multipliedBy(2)))
        assertEquals("basic-p1m-krw", first.code)
        assertEquals("basic-p1m-krw-2", sale.code)
        assertEquals(CatalogErrorCode.CODE_TAKEN, code(assertFailsWith<ApplicationException> { krw(amount = 5_000, code = "basic-p1m-krw") }))
    }

    @Test
    fun `there is no way to change what a price costs - the end of a sale and the provider links are all that can move`() {
        basic()
        val sale = krw(amount = 2_900, compareAt = 3_900, starts = now.plus(day), ends = now.plus(day.multipliedBy(3)))

        val extended = admin.updateSaleEnd("adm_1", sale.code, now.plus(day.multipliedBy(5)))

        assertEquals(now.plus(day.multipliedBy(5)), extended.saleEndsAt)
        val stored = store.prices.getValue(sale.code)
        assertEquals(listOf(2_900L, "KRW", "P1M", 3_900L), listOf(stored.amount, stored.currency, stored.period, stored.compareAtAmount))
        assertEquals(setOf("updateSaleEnd"), CatalogAdmin::class.java.methods.map { it.name }.filter { it.startsWith("update") && it != "updateProduct" }.toSet(), "no other price-changing method exists")
    }

    @Test
    fun `an end that would overlap another sale, or a change on a stopped price or an evergreen price, is refused`() {
        basic()
        val first = krw(amount = 2_900, starts = now.plus(day), ends = now.plus(day.multipliedBy(2)))
        krw(amount = 2_500, starts = now.plus(day.multipliedBy(3)), ends = now.plus(day.multipliedBy(4)))
        val evergreen = krw()

        assertEquals(CatalogErrorCode.PRICE_OVERLAP, code(assertFailsWith<ApplicationException> { admin.updateSaleEnd("adm_1", first.code, now.plus(day.multipliedBy(3)).plusSeconds(1)) }))
        assertEquals(CatalogErrorCode.PRICE_INVALID, code(assertFailsWith<ApplicationException> { admin.updateSaleEnd("adm_1", evergreen.code, now.plus(day)) }))
        assertEquals(CatalogErrorCode.PRICE_INVALID, code(assertFailsWith<ApplicationException> { admin.updateSaleEnd("adm_1", first.code, now) }), "an end before the start")
        admin.stopPrice("adm_1", first.code)
        assertEquals(CatalogErrorCode.PRICE_INVALID, code(assertFailsWith<ApplicationException> { admin.updateSaleEnd("adm_1", first.code, now.plus(day.multipliedBy(2))) }))
        assertEquals(CatalogErrorCode.NOT_FOUND, code(assertFailsWith<ApplicationException> { admin.updateSaleEnd("adm_1", "missing", null) }))
    }

    @Test
    fun `stopping a price is final and idempotent`() {
        basic()
        val price = krw()

        val stopped = admin.stopPrice("adm_1", price.code)
        val again = admin.stopPrice("adm_1", price.code)

        assertFalse(stopped.selling)
        assertNotNull(stopped.stoppedAt)
        assertFalse(again.selling)
        assertEquals(1, audit.actions.count { it.action == "catalog.price.stopped" }, "the second press records nothing")
    }

    @Test
    fun `provider links are set, replaced and removed, and one provider price id belongs to one price`() {
        basic()
        val monthly = krw()
        val usd = admin.addPrice("adm_1", "basic", PriceInput(period = "P1M", amount = 300, currency = "USD"))

        assertEquals(mapOf("paddle" to "pri_1"), admin.setProviderRef("adm_1", usd.code, "Paddle", "pri_1").providerRefs)
        assertEquals(mapOf("paddle" to "pri_2"), admin.setProviderRef("adm_1", usd.code, "paddle", "pri_2").providerRefs)
        assertEquals(CatalogErrorCode.CODE_TAKEN, code(assertFailsWith<ApplicationException> { admin.setProviderRef("adm_1", monthly.code, "paddle", "pri_2") }))
        assertEquals(emptyMap(), admin.removeProviderRef("adm_1", usd.code, "paddle").providerRefs)
        assertFailsWith<ApplicationException> { admin.setProviderRef("adm_1", usd.code, "paddle", "has space") }
    }

    @Test
    fun `a product is edited without touching its code, kind or prices, and a level taken by another product is refused`() {
        basic()
        basic(level = 2, code = "standard")
        krw()

        val edited = admin.updateProduct("adm_1", "basic", ProductPatch(highlighted = true, selling = false, sortOrder = 5, texts = mapOf("en" to ProductTextInput("Basic plan"))))

        assertTrue(edited.product.highlighted)
        assertFalse(edited.product.selling)
        assertEquals(listOf("Basic plan"), edited.product.texts.map { it.name })
        assertEquals(1, edited.prices.size)
        assertEquals(CatalogErrorCode.LEVEL_TAKEN, code(assertFailsWith<ApplicationException> { admin.updateProduct("adm_1", "basic", ProductPatch(level = 2)) }))
        assertEquals(1, store.products.getValue("basic").level)
        assertEquals(CatalogErrorCode.NOT_FOUND, code(assertFailsWith<ApplicationException> { admin.updateProduct("adm_1", "missing", ProductPatch()) }))
    }

    @Test
    fun `every successful write leaves one audit line and a refused one leaves none`() {
        basic()
        val price = krw()
        admin.setProviderRef("adm_1", price.code, "paddle", "pri_1")
        admin.stopPrice("adm_1", price.code)
        assertFailsWith<ApplicationException> { krw(amount = 0) }

        assertEquals(listOf("catalog.product.created", "catalog.price.created", "catalog.price.link-set", "catalog.price.stopped"), audit.actions.map { it.action })
        assertTrue(audit.actions.all { it.actorId == "adm_1" })
        assertNull(audit.actions.firstOrNull { it.action == "catalog.price.created" }?.detail?.get("secret"))
    }
}

/** 가격 입력의 규칙 둘 - 행사에는 정가가 있고 상시에는 없다 · 끝만 있는 행사는 지금 시작한다 · 옛 가격을 대신하는 새 가격은 한 번에 · 언어 태그는 정규화 · 링크는 가격 id 를 쓰는 결제사에만. */
class CatalogAdminPriceRulesTest {
    private val store = FakeCatalogStore()
    private val time = MutableTime()
    private val admin = CatalogAdmin(store, time, RecordingAudit(), providers = { listOf("paddle") })
    private val day = Duration.ofDays(1)

    init {
        admin.createProduct("a", ProductInput("basic", "SUBSCRIPTION", 1, texts = TEXTS))
    }

    private fun field(error: Throwable) = (error as dev.sumin.skeleton.common.FieldValidationException).field

    @Test
    fun `a sale needs its list price and an evergreen price may not have one`() {
        val noList = assertFailsWith<ApplicationException> { admin.addPrice("a", "basic", PriceInput(period = "P1M", amount = 2_900, currency = "KRW", saleEndsAt = time.now().plus(day))) }
        assertEquals("compareAtAmount", field(noList))
        assertEquals(CatalogErrorCode.PRICE_INVALID, noList.errorCode)
        val evergreenWithList = assertFailsWith<ApplicationException> { admin.addPrice("a", "basic", PriceInput(period = "P1M", amount = 2_900, currency = "KRW", compareAtAmount = 3_900)) }
        assertEquals("compareAtAmount", field(evergreenWithList))
        assertEquals(emptyMap(), store.prices)
    }

    @Test
    fun `a sale with only an end starts now`() {
        val sale = admin.addPrice("a", "basic", PriceInput(period = "P1M", amount = 2_900, currency = "KRW", compareAtAmount = 3_900, saleEndsAt = time.now().plus(day)))
        assertEquals(time.now(), sale.saleStartsAt)
    }

    @Test
    fun `a replacing price stops the old one and is created in one step, and a failed replacement stops nothing`() {
        val old = admin.addPrice("a", "basic", PriceInput(period = "P1M", amount = 3_900, currency = "KRW"))

        val fresh = admin.addPrice("a", "basic", PriceInput(period = "P1M", amount = 4_900, currency = "KRW", replaces = old.code))

        assertFalse(store.prices.getValue(old.code).selling)
        assertTrue(store.prices.getValue(fresh.code).selling)
        assertFailsWith<ApplicationException> { admin.addPrice("a", "basic", PriceInput(period = "P1M", amount = 0, currency = "KRW", replaces = fresh.code)) }
        assertTrue(store.prices.getValue(fresh.code).selling, "the refused replacement left the old price selling")
        assertEquals("replaces", field(assertFailsWith<ApplicationException> { admin.addPrice("a", "basic", PriceInput(period = "P1M", amount = 5_000, currency = "KRW", replaces = "missing")) }))
    }

    @Test
    fun `language tags are made canonical and a duplicate after that is refused`() {
        val product = admin.createProduct("a", ProductInput("pro", "SUBSCRIPTION", 2, texts = mapOf("EN-us" to ProductTextInput("Pro"), "ko" to ProductTextInput("프로")))).product
        assertEquals(listOf("en-US", "ko"), product.texts.map { it.locale })
        assertEquals("texts", field(assertFailsWith<ApplicationException> { admin.createProduct("a", ProductInput("x", "ONE_TIME", null, texts = mapOf("en" to ProductTextInput("A"), "EN" to ProductTextInput("B")))) }))
    }

    @Test
    fun `a provider link only goes to a provider that uses price ids`() {
        val price = admin.addPrice("a", "basic", PriceInput(period = "P1M", amount = 300, currency = "USD"))
        assertEquals("provider", field(assertFailsWith<ApplicationException> { admin.setProviderRef("a", price.code, "toss", "pri_1") }))
        assertEquals(mapOf("paddle" to "pri_1"), admin.setProviderRef("a", price.code, "paddle", "pri_1").providerRefs)
    }
}
