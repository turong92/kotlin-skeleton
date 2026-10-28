package dev.sumin.skeleton.catalog

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

/** 공개 읽기: 통화별로 그 통화의 현재 가격만, 통화를 모르면 상품마다 사전순 첫 통화, 그 통화 가격이 없으면 거르지 않는다. */
class CatalogReaderTest {
    private val store = FakeCatalogStore()
    private val time = MutableTime()
    private val admin = CatalogAdmin(store, time, RecordingAudit())
    private val reader = CatalogReader(store, time)

    init {
        admin.createProduct("a", ProductInput("basic", "SUBSCRIPTION", 1, texts = TEXTS))
        admin.createProduct("a", ProductInput("pro", "SUBSCRIPTION", 2, highlighted = true, sortOrder = 1, texts = TEXTS))
        admin.createProduct("a", ProductInput("sticker", "ONE_TIME", null, sortOrder = 2, texts = TEXTS))
        listOf("basic" to 3_900L, "pro" to 9_900L).forEach { (p, krw) ->
            admin.addPrice("a", p, PriceInput(p, "P1M", krw, "KRW"))
            admin.addPrice("a", p, PriceInput("$p-usd", "P1M", krw / 1_000 * 100, "USD"))
        }
        admin.addPrice("a", "basic", PriceInput("basic-yearly", "P1Y", 39_000, "KRW"))
        admin.addPrice("a", "sticker", PriceInput("sticker-usd", null, 200, "USD"))
    }

    private fun codes(currency: String?, kind: ProductKind? = null) = reader.read(kind, currency).products.associate { it.code to it.prices.map { p -> p.code } }

    @Test
    fun `a KRW request sees the KRW current prices only and a USD request the USD ones`() {
        assertEquals(mapOf("basic" to listOf("basic", "basic-yearly"), "pro" to listOf("pro")), codes("KRW"))
        assertEquals(mapOf("basic" to listOf("basic-usd"), "pro" to listOf("pro-usd"), "sticker" to listOf("sticker-usd")), codes("USD"))
        assertEquals("USD", reader.read(null, "usd").currency)
    }

    @Test
    fun `when the request currency is unknown every product shows its first currency in alphabetical order`() {
        assertEquals(mapOf("basic" to listOf("basic", "basic-yearly"), "pro" to listOf("pro"), "sticker" to listOf("sticker-usd")), codes(null))
        assertEquals(null, reader.read(null, null).currency)
    }

    @Test
    fun `a currency nothing is priced in falls to the unknown-currency rule`() {
        assertEquals(codes(null), codes("JPY"))
        assertEquals("JPY", reader.read(null, "JPY").currency)
    }

    @Test
    fun `the kind filter, the product order, a stopped product and a stopped price`() {
        assertEquals(listOf("sticker"), reader.read(ProductKind.ONE_TIME, "USD").products.map { it.code })
        assertEquals(listOf("basic", "pro"), reader.read(ProductKind.SUBSCRIPTION, "KRW").products.map { it.code })

        admin.updateProduct("a", "pro", ProductPatch(selling = false))
        admin.stopPrice("a", "basic-yearly")
        assertEquals(mapOf("basic" to listOf("basic")), codes("KRW"))
    }

    @Test
    fun `a sale shows the sale price with its list price and end, until the end instant`() {
        val now = time.now()
        admin.addPrice("a", "pro", PriceInput("pro-sale", "P1M", 7_900, "KRW", 9_900, now, now.plus(Duration.ofDays(1))))

        val sale = reader.read(null, "KRW").products.single { it.code == "pro" }.prices.single()
        assertEquals(listOf("pro-sale", 7_900L, 9_900L, now.plus(Duration.ofDays(1))), listOf(sale.code, sale.amount, sale.compareAtAmount, sale.saleEndsAt))

        time.current = now.plus(Duration.ofDays(1))
        assertEquals(listOf("pro"), reader.read(null, "KRW").products.single { it.code == "pro" }.prices.map { it.code })
    }
}
