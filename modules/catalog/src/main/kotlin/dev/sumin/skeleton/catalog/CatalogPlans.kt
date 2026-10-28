package dev.sumin.skeleton.catalog

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentCatalog
import dev.sumin.skeleton.payment.PaymentItemNotPurchasableException
import dev.sumin.skeleton.payment.ProviderPriceRefs
import dev.sumin.skeleton.payment.PurchaseQuote
import dev.sumin.skeleton.payment.PurchaseRequest
import dev.sumin.skeleton.payment.SubscriptionPlan
import dev.sumin.skeleton.payment.SubscriptionPlans

/** 가격 코드로 찾은 가격과 그 상품. [purchasable] = 상품이 판매 중이고 이 가격이 지금의 현재 가격이다(지난 · 중지 · 예정 · 행사에 가려진 상시 가격은 false). */
internal class LocatedPrice(val product: CatalogProduct, val price: CatalogPrice, val purchasable: Boolean)

internal class PriceLookup(private val store: CatalogStore, private val time: TimeProvider) {
    fun byCode(code: String): LocatedPrice? {
        val price = store.findPrice(code) ?: return null
        val product = store.findProductById(price.productId) ?: return null
        val purchasable = product.selling && CurrentPrices.isCurrent(price, store.listPricesOf(product.id), time.now())
        return LocatedPrice(product, price, purchasable)
    }
}

/**
 * 카탈로그 표를 구독 플랜으로 읽는다 - **플랜 한 줄 = 가격 하나**, 플랜 코드 = 가격 코드.
 * [list] 는 판매 중인 구독 상품의 현재 가격들(모든 통화 - 요청 통화로 거르는 것은 결제 쪽이다), [find] 는 판매가 끝난 가격까지 찾는다(기존 구독자의 이름 · 등급 · 주기) -
 * 새로 살 수 있는지는 [SubscriptionPlan.purchasable] 이 가른다. 이름은 [CatalogProperties.defaultLocale] → 첫 언어.
 */
class CatalogSubscriptionPlans(
    private val store: CatalogStore,
    private val time: TimeProvider,
    private val properties: CatalogProperties,
) : SubscriptionPlans {
    private val lookup = PriceLookup(store, time)

    override fun list(): List<SubscriptionPlan> {
        val now = time.now()
        val prices = store.listPrices().groupBy { it.productId }
        return store.listProducts()
            .filter { it.selling && it.kind == ProductKind.SUBSCRIPTION }
            .flatMap { product -> CurrentPrices.current(prices[product.id].orEmpty(), now).map { plan(product, it, purchasable = true) } }
    }

    override fun find(code: String): SubscriptionPlan? {
        val found = lookup.byCode(code)?.takeIf { it.product.kind == ProductKind.SUBSCRIPTION } ?: return null
        return plan(found.product, found.price, found.purchasable)
    }

    private fun plan(product: CatalogProduct, price: CatalogPrice, purchasable: Boolean) = SubscriptionPlan(
        code = price.code,
        name = product.textIn(properties.defaultLocale).name,
        amount = PaymentAmount(price.amount, price.currency),
        period = checkNotNull(price.period) { "a subscription price has a period: ${price.code}" },
        level = checkNotNull(product.level) { "a subscription product has a level: ${product.code}" },
        productCode = product.code,
        purchasable = purchasable,
        regularAmount = price.compareAtAmount,
    )
}

/**
 * 단건 상품을 [PaymentCatalog] 로 판다 - `itemType` 은 [CatalogProperties.itemType], `itemRef` 는 가격 코드, 수량은 1, 금액은 **지금의 현재 가격**이다.
 * 결제 계약의 `PaymentCatalog` 빈은 하나뿐이라 앱이 코드 상품(예: 응원하기)을 따로 두면 자기 `PaymentCatalog` 에서 `itemType` 으로 갈라 이 클래스를 부른다.
 * 살 수 없으면 [PaymentItemNotPurchasableException].
 */
class CatalogPaymentCatalog(
    private val store: CatalogStore,
    private val time: TimeProvider,
    private val properties: CatalogProperties,
) : PaymentCatalog {
    private val lookup = PriceLookup(store, time)

    override fun quote(request: PurchaseRequest): PurchaseQuote {
        if (request.itemType != properties.itemType || request.quantity != 1) throw PaymentItemNotPurchasableException()
        val found = lookup.byCode(request.itemRef)?.takeIf { it.purchasable && it.product.kind == ProductKind.ONE_TIME } ?: throw PaymentItemNotPurchasableException()
        return PurchaseQuote(PaymentAmount(found.price.amount, found.price.currency), found.product.textIn(properties.defaultLocale).name, found.product.code)
    }
}

/** 가격에 붙인 결제사 링크 - 결제사 모듈이 가격 id 를 먼저 여기서 찾는다. */
class CatalogProviderPriceRefs(private val store: CatalogStore) : ProviderPriceRefs {
    override fun priceIdOf(provider: String, itemRef: String): String? = store.providerRefOf(provider.trim().lowercase(), itemRef)

    override fun all(provider: String): Map<String, String> = store.providerRefs(provider.trim().lowercase())
}
