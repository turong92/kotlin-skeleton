package dev.sumin.skeleton.catalog

import dev.sumin.skeleton.common.authz.OperatorAction
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Instant

class MutableTime(var current: Instant = Instant.parse("2026-10-29T00:00:00Z")) : TimeProvider {
    override fun now(): Instant = current
}

/** 메모리 저장소 대역 - 트랜잭션은 모니터 하나라 동시 호출이 줄을 선다(되돌리기는 없다). 유니크 규칙은 JDBC 구현과 같은 예외로 */
class FakeCatalogStore : CatalogStore {
    val products = LinkedHashMap<String, CatalogProduct>()
    val prices = LinkedHashMap<String, CatalogPrice>()
    val refs = LinkedHashMap<Triple<String, String, String>, Unit>()   // (priceCode, provider, externalId)

    override fun <T> inTransaction(block: () -> T): T = synchronized(this) { block() }

    override fun listProducts() = products.values.sortedWith(compareBy({ it.sortOrder }, { it.id }))
    override fun findProduct(code: String) = products[code]
    override fun findProductById(id: String) = products.values.firstOrNull { it.id == id }
    override fun lockProduct(code: String) = products[code]
    override fun productCodeOfLevel(level: Int) = products.values.firstOrNull { it.level == level }?.code

    override fun insertProduct(product: CatalogProduct) {
        if (products.containsKey(product.code)) throw CatalogException(CatalogErrorCode.CODE_TAKEN, "code")
        if (product.level != null && productCodeOfLevel(product.level) != null) throw CatalogException(CatalogErrorCode.LEVEL_TAKEN, "level")
        products[product.code] = product
    }

    override fun updateProduct(product: CatalogProduct) {
        products[product.code] = product
    }

    override fun listPrices() = prices.values.map(::withRefs)
    override fun listPricesOf(productId: String) = prices.values.filter { it.productId == productId }.map(::withRefs)
    override fun findPrice(code: String) = prices[code]?.let(::withRefs)

    override fun insertPrice(price: CatalogPrice) {
        if (prices.containsKey(price.code)) throw CatalogException(CatalogErrorCode.CODE_TAKEN, "price code")
        prices[price.code] = price
    }

    override fun stopPrice(code: String, at: Instant): Boolean {
        val row = prices[code]?.takeIf { it.selling } ?: return false
        prices[code] = row.copy(selling = false, stoppedAt = at, updatedAt = at)
        return true
    }

    override fun updateSaleEnd(code: String, endsAt: Instant?, at: Instant): Boolean {
        val row = prices[code] ?: return false
        prices[code] = row.copy(saleEndsAt = endsAt, updatedAt = at)
        return true
    }

    override fun putProviderRef(priceCode: String, provider: String, externalId: String, at: Instant): Boolean {
        if (refs.keys.any { it.second == provider && it.third == externalId && it.first != priceCode }) return false
        refs.keys.removeAll { it.first == priceCode && it.second == provider }
        refs[Triple(priceCode, provider, externalId)] = Unit
        return true
    }

    override fun deleteProviderRef(priceCode: String, provider: String) = refs.keys.removeAll { it.first == priceCode && it.second == provider }

    override fun providerRefs(provider: String) = refs.keys.filter { it.second == provider }.associate { it.first to it.third }
    override fun providerRefOf(provider: String, priceCode: String) = refs.keys.firstOrNull { it.first == priceCode && it.second == provider }?.third

    private fun withRefs(price: CatalogPrice) = price.copy(providerRefs = refs.keys.filter { it.first == price.code }.associate { it.second to it.third })
}

class RecordingAudit : OperatorAuditLog {
    val actions = mutableListOf<OperatorAction>()

    override fun record(action: OperatorAction) {
        actions += action
    }
}

val TEXTS = mapOf("en" to ProductTextInput("Basic", "The basics"), "ko" to ProductTextInput("베이직", null))
