package dev.sumin.skeleton.catalog.jdbc

import dev.sumin.skeleton.catalog.CatalogAdmin
import dev.sumin.skeleton.catalog.CatalogErrorCode
import dev.sumin.skeleton.catalog.CatalogException
import dev.sumin.skeleton.catalog.CatalogPaymentCatalog
import dev.sumin.skeleton.catalog.CatalogProperties
import dev.sumin.skeleton.catalog.CatalogStore
import dev.sumin.skeleton.catalog.CatalogSubscriptionPlans
import dev.sumin.skeleton.catalog.PriceInput
import dev.sumin.skeleton.catalog.ProductInput
import dev.sumin.skeleton.catalog.ProductPatch
import dev.sumin.skeleton.catalog.ProductTextInput
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/** 두 방언(postgresTest · mysqlTest)에서: 왕복, 유니크가 「하나뿐」을 마지막에 지킨다, 상품 줄 잠금 아래 동시 행사 추가는 하나만, 결제 계약이 DB 표를 읽는다. */
@Import(DbTestcontainers::class)
@SpringBootTest(classes = [CatalogJdbcTestApplication::class])
class JdbcCatalogStoreDbTest {
    @Autowired lateinit var store: CatalogStore
    @Autowired lateinit var admin: CatalogAdmin

    private val seq = UUID.randomUUID().toString().take(8)
    private val texts = mapOf("en" to ProductTextInput("Basic", "The basics"), "ko" to ProductTextInput("베이직"))
    private fun subscription(code: String = "basic-$seq", lvl: Int = nextLevel()) = admin.createProduct("adm_1", ProductInput(code, "SUBSCRIPTION", lvl, true, 3, texts)).product

    private fun nextLevel() = levels.getAndIncrement()

    private companion object {
        val levels = java.util.concurrent.atomic.AtomicInteger(1)   // 시험마다 새 인스턴스지만 DB 는 클래스가 같이 쓴다 - 등급이 겹치지 않게 하나씩
    }

    private fun <T> concurrently(count: Int, block: (Int) -> T): List<Result<T>> {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(count)
        val futures = (0 until count).map { i -> pool.submit<Result<T>> { start.await(); runCatching { block(i) } } }
        start.countDown()
        return futures.map { it.get(60, TimeUnit.SECONDS) }.also { pool.shutdown() }
    }

    @Test
    fun `a product and its prices make the round trip with every column, newest first in the admin view`() {
        val product = subscription()
        val starts = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val ends = starts.plus(Duration.ofDays(2))
        val regular = admin.addPrice("adm_1", product.code, PriceInput("${product.code}-m", "P1M", 3_900, "KRW"))
        val sale = admin.addPrice("adm_1", product.code, PriceInput("${product.code}-sale", "P1M", 2_900, "KRW", 3_900, starts, ends))

        val read = store.findProduct(product.code)!!
        assertEquals(listOf(product.code, "SUBSCRIPTION", product.level, true, 3), listOf(read.code, read.kind.name, read.level, read.highlighted, read.sortOrder))
        assertEquals(mapOf("en" to "The basics", "ko" to null), read.texts.associate { it.locale to it.description })
        assertEquals("베이직", read.texts.single { it.locale == "ko" }.name)
        val savedSale = store.findPrice(sale.code)!!
        assertEquals(listOf(2_900L, 3_900L, "KRW", "P1M", starts, ends, true), listOf(savedSale.amount, savedSale.compareAtAmount, savedSale.currency, savedSale.period, savedSale.saleStartsAt, savedSale.saleEndsAt, savedSale.selling))
        assertNull(store.findPrice(regular.code)!!.compareAtAmount)
        assertEquals(listOf(sale.code, regular.code), admin.entry(product.code).prices.map { it.code })
        assertEquals(setOf(regular.code, sale.code), store.listPricesOf(product.id).map { it.code }.toSet())
    }

    @Test
    fun `a product edit replaces the texts and moves only the editable columns`() {
        val product = subscription()
        admin.updateProduct("adm_1", product.code, ProductPatch(selling = false, sortOrder = 9, texts = mapOf("en" to ProductTextInput("Renamed"))))

        val read = store.findProduct(product.code)!!
        assertEquals(listOf(false, 9, product.level, listOf("Renamed")), listOf(read.selling, read.sortOrder, read.level, read.texts.map { it.name }))
    }

    @Test
    fun `the unique keys hold even when the checks race - the same code, level and price code from many threads leave one row`() {
        val code = "race-$seq"
        val lvl = nextLevel()
        val products = concurrently(4) { admin.createProduct("adm_1", ProductInput(code, "SUBSCRIPTION", lvl, texts = texts)) }
        assertEquals(1, products.count { it.isSuccess })
        assertTrue(products.filter { it.isFailure }.all { (it.exceptionOrNull() as CatalogException).catalogError in setOf(CatalogErrorCode.CODE_TAKEN, CatalogErrorCode.LEVEL_TAKEN) })

        val other = concurrently(3) { i -> admin.createProduct("adm_1", ProductInput("level-$seq-$i", "SUBSCRIPTION", lvl, texts = texts)) }
        assertEquals(0, other.count { it.isSuccess }, "the level already belongs to $code")
        assertTrue(other.all { (it.exceptionOrNull() as CatalogException).catalogError == CatalogErrorCode.LEVEL_TAKEN })
    }

    @Test
    fun `two sales of one product and currency added at once overlap - exactly one is created, the other is PRICE_OVERLAP`() {
        val product = subscription()
        val from = Instant.now().plus(Duration.ofHours(1))
        val results = concurrently(5) { i -> admin.addPrice("adm_1", product.code, PriceInput("${product.code}-s$i", "P1M", 2_000L + i, "KRW", 9_000L, from, from.plus(Duration.ofDays(1)))) }

        assertEquals(1, results.count { it.isSuccess })
        assertTrue(results.filter { it.isFailure }.all { (it.exceptionOrNull() as CatalogException).catalogError == CatalogErrorCode.PRICE_OVERLAP })
        assertEquals(1, store.listPricesOf(product.id).size)
    }

    @Test
    fun `two evergreen prices added at once leave one, and the same price code twice is a 409 CODE_TAKEN`() {
        val product = subscription()
        val results = concurrently(4) { i -> admin.addPrice("adm_1", product.code, PriceInput(null, "P1Y", 30_000L + i, "KRW")) }
        assertEquals(1, results.count { it.isSuccess })

        val sameCode = concurrently(3) { i -> admin.addPrice("adm_1", product.code, PriceInput("${product.code}-same", "P3M", 9_000L + i, "USD")) }
        assertEquals(1, sameCode.count { it.isSuccess })
        assertTrue(sameCode.filter { it.isFailure }.all { (it.exceptionOrNull() as CatalogException).catalogError in setOf(CatalogErrorCode.CODE_TAKEN, CatalogErrorCode.PRICE_OVERLAP) })
    }

    @Test
    fun `a stop is final and keeps its time, a sale end can move`() {
        val product = subscription()
        val sale = admin.addPrice("adm_1", product.code, PriceInput(null, "P1M", 2_000, "KRW", 3_000, Instant.now().plusSeconds(60), Instant.now().plusSeconds(3_600)))
        val moved = Instant.now().plusSeconds(7_200).truncatedTo(ChronoUnit.MICROS)

        admin.updateSaleEnd("adm_1", sale.code, moved)
        admin.stopPrice("adm_1", sale.code)
        val again = admin.stopPrice("adm_1", sale.code)

        val read = store.findPrice(sale.code)!!
        assertEquals(moved, read.saleEndsAt)
        assertEquals(false, read.selling)
        assertNotNull(read.stoppedAt)
        assertEquals(read.stoppedAt, again.stoppedAt)
    }

    @Test
    fun `a provider link is unique per provider id, can be replaced and removed, and is read by price code`() {
        val product = subscription()
        val first = admin.addPrice("adm_1", product.code, PriceInput("${product.code}-usd", "P1M", 300, "USD"))
        val second = admin.addPrice("adm_1", product.code, PriceInput("${product.code}-usd-y", "P1Y", 3_000, "USD"))

        val admin = CatalogAdmin(store, TimeProvider.systemUtc(), { }, providers = { listOf("paddle") })   // 가격 id 를 쓰는 결제사가 있는 조립
        admin.setProviderRef("adm_1", first.code, "paddle", "pri_$seq")
        assertEquals(CatalogErrorCode.CODE_TAKEN, assertFailsWith<CatalogException> { admin.setProviderRef("adm_1", second.code, "paddle", "pri_$seq") }.catalogError)
        admin.setProviderRef("adm_1", first.code, "paddle", "pri_${seq}_b")
        admin.setProviderRef("adm_1", second.code, "paddle", "pri_$seq")

        assertEquals("pri_${seq}_b", store.providerRefOf("paddle", first.code))
        assertEquals(mapOf(first.code to "pri_${seq}_b", second.code to "pri_$seq"), store.providerRefs("paddle").filterKeys { it.startsWith(product.code) })
        assertEquals(mapOf("paddle" to "pri_${seq}_b"), store.findPrice(first.code)!!.providerRefs)
        admin.removeProviderRef("adm_1", first.code, "paddle")
        assertNull(store.providerRefOf("paddle", first.code))
    }

    @Test
    fun `the payment contracts read the database table - plans, the one-time catalog and the existing subscriber's price after a stop`() {
        val product = subscription()
        val monthly = admin.addPrice("adm_1", product.code, PriceInput("${product.code}-m", "P1M", 3_900, "KRW"))
        val properties = CatalogProperties(defaultLocale = "ko")
        val plans = CatalogSubscriptionPlans(store, TimeProvider.systemUtc(), properties)

        assertEquals(listOf(monthly.code), plans.list().filter { it.productCode == product.code }.map { it.code })
        admin.stopPrice("adm_1", monthly.code)

        val found = plans.find(monthly.code)!!
        assertEquals(listOf(false, 3_900L, "베이직"), listOf(found.purchasable, found.amount.amount, found.name))
        assertTrue(plans.list().none { it.code == monthly.code })
        assertEquals(CatalogPaymentCatalog::class, CatalogPaymentCatalog(store, TimeProvider.systemUtc(), properties)::class)
    }
}
