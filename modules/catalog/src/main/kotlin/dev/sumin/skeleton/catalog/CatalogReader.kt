package dev.sumin.skeleton.catalog

import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Instant

data class PublicPrice(
    val code: String,
    val period: String?,
    val amount: Long,
    val currency: String,
    val compareAtAmount: Long?,
    val saleEndsAt: Instant?,
)

data class PublicProduct(
    val code: String,
    val kind: ProductKind,
    val level: Int?,
    val highlighted: Boolean,
    val sortOrder: Int,
    val texts: List<CatalogProductText>,
    val prices: List<PublicPrice>,
)

/** [currency] 는 이 요청의 통화(없으면 null - 그때는 상품마다 사전순 첫 통화의 가격이다) */
data class PublicCatalog(val currency: String?, val products: List<PublicProduct>)

/**
 * 로그인 없이 보이는 카탈로그 - 판매 중인 상품과 그 **현재 가격**만. 가격은 이 요청의 통화로 거른다.
 * - 통화를 알고 그 통화의 현재 가격이 하나라도 있으면: 그 통화 가격만(가격이 없어진 상품은 뺀다).
 * - 통화를 모르거나 그 통화 가격이 하나도 없으면: 상품마다 가진 현재 가격 가운데 사전순 첫 통화 (`/payments/subscriptions/plans` 와 같은 규칙).
 */
class CatalogReader(private val store: CatalogStore, private val time: TimeProvider) {
    fun read(kind: ProductKind?, currency: String?): PublicCatalog {
        val now = time.now()
        val prices = store.listPrices().groupBy { it.productId }
        val current = store.listProducts()
            .filter { it.selling && (kind == null || it.kind == kind) }
            .map { it to CurrentPrices.current(prices[it.id].orEmpty(), now) }
        val wanted = currency?.trim()?.uppercase()
        val inWanted = wanted != null && current.any { (_, list) -> list.any { it.currency == wanted } }
        val products = current.mapNotNull { (product, list) ->
            val shown = when {
                inWanted -> list.filter { it.currency == wanted }
                else -> list.minOfOrNull { it.currency }?.let { first -> list.filter { it.currency == first } }.orEmpty()
            }
            if (shown.isEmpty()) null else PublicProduct(product.code, product.kind, product.level, product.highlighted, product.sortOrder, product.texts, shown.map { it.toPublic() })
        }
        return PublicCatalog(wanted, products)
    }

    private fun CatalogPrice.toPublic() = PublicPrice(code, period, amount, currency, compareAtAmount, saleEndsAt)
}
