package dev.sumin.skeleton.app.sample.reviews

import dev.sumin.skeleton.catalog.CatalogProperties
import dev.sumin.skeleton.catalog.CatalogStore
import dev.sumin.skeleton.payment.PaymentOrder
import dev.sumin.skeleton.payment.PaymentOrderStatus
import dev.sumin.skeleton.payment.SubscriptionLifecycle
import dev.sumin.skeleton.review.ReviewPurchases
import dev.sumin.skeleton.review.ReviewTarget
import org.springframework.stereotype.Component

/**
 * 결제 → 리뷰 구매 신호. 구독 주문(`itemRef` = 플랜 코드)과 카탈로그 단건 주문(`itemRef` = 가격 코드)은 둘 다 **가격 코드**를 들고 있으니, 카탈로그 표에서 상품 코드를 찾아
 * `ReviewPurchases.record(계정, product:<상품 코드>, "order:<주문 id>")` 를 부른다 - 같은 주문이 두 번 와도 한 줄이다. 전액 환불이면 `revoke`(일부 환불은 그대로).
 * 응원하기 같은 코드 상품(`cheer`)은 리뷰 대상이 아니다. 탈퇴한 계정의 늦은 주문은 신호를 남기지 않는다. 주문 줄 락 안에서 불리므로 줄 하나만 쓴다.
 */
@Component
class ReviewPurchaseSignals(
    private val purchases: ReviewPurchases,
    private val store: CatalogStore,
    properties: CatalogProperties,
) {
    private val itemTypes = setOf(SubscriptionLifecycle.ITEM_TYPE, properties.itemType)

    fun onPaid(order: PaymentOrder) {
        if (order.itemType !in itemTypes || order.accountErasedAt != null) return
        val product = productCodeOf(order.itemRef) ?: return
        purchases.record(order.accountId, ReviewTarget(TARGET_TYPE, product), ref(order))
    }

    fun onRefunded(order: PaymentOrder) {
        if (order.itemType !in itemTypes) return
        val full = order.status == PaymentOrderStatus.REFUNDED || order.refundedAmount >= order.amount.amount
        if (full) purchases.revoke(ref(order))
    }

    private fun productCodeOf(priceCode: String): String? {
        val price = store.findPrice(priceCode) ?: return null
        return store.findProductById(price.productId)?.code
    }

    private fun ref(order: PaymentOrder) = "order:${order.id}"

    companion object {
        /** 리뷰 대상 종류 - `skeleton.review.target-types` 와 같다 */
        const val TARGET_TYPE = "product"
    }
}
