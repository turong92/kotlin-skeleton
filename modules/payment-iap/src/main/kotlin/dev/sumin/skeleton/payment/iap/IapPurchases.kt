package dev.sumin.skeleton.payment.iap

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.http.ExternalHttpException
import dev.sumin.skeleton.payment.PaymentAlreadySubscribedException
import dev.sumin.skeleton.payment.PaymentCatalog
import dev.sumin.skeleton.payment.PaymentLedgerStore
import dev.sumin.skeleton.payment.PaymentProviderNotFoundException
import dev.sumin.skeleton.payment.PaymentCapabilityUnsupportedException
import dev.sumin.skeleton.payment.PaymentProviderRouter
import dev.sumin.skeleton.payment.PaymentStoreGateway
import dev.sumin.skeleton.payment.PaymentStorePurchases
import dev.sumin.skeleton.payment.PaymentSubscription
import dev.sumin.skeleton.payment.PaymentUnavailableException
import dev.sumin.skeleton.payment.PurchaseRequest
import dev.sumin.skeleton.payment.StoreClaim
import dev.sumin.skeleton.payment.StoreProduct
import dev.sumin.skeleton.payment.StorePurchase
import dev.sumin.skeleton.payment.StorePurchaseKind
import dev.sumin.skeleton.payment.StorePurchaseState
import dev.sumin.skeleton.payment.StoreRecordOutcome
import dev.sumin.skeleton.payment.SubscriptionPlans
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider

/** 구매 확인의 결과 - 지급했거나 이미 지급됨([Granted]), 스토어가 아직 결제 대기([Pending]). 그 밖은 [dev.sumin.skeleton.common.ApplicationException] 이다. */
sealed interface IapResult {
    data class Granted(val kind: StorePurchaseKind, val orderId: String?, val subscription: PaymentSubscription?) : IapResult

    data object Pending : IapResult
}

/**
 * 앱이 스토어에서 산 것을 서버가 스토어에 직접 물어 확인하고 장부에 싣는다(스펙 §3). **스토어 호출은 락 · 트랜잭션 밖**이다 - [PaymentStorePurchases.record] 만 트랜잭션을 연다.
 * 순서: 스토어에 묻기 → 검사(환불 · 가족 공유 · 수량) → 상품 지도 → 장부 기록. 앱이 보낸 값은 스토어에 무엇을 물을지 가리킬 뿐이고 믿는 것은 스토어의 답이다.
 */
class IapPurchases(
    private val router: PaymentProviderRouter,
    private val purchases: PaymentStorePurchases,
    private val products: IapProducts,
    private val catalog: ObjectProvider<PaymentCatalog>,
    private val plans: ObjectProvider<SubscriptionPlans>,
    private val ledger: PaymentLedgerStore,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 이 스토어의 켜진 결제사 - 꺼져 있거나 키가 없어 서지 않았으면 null. */
    fun gatewayOf(store: String): PaymentStoreGateway? =
        try {
            router.gateway(store, PaymentStoreGateway::class)
        } catch (e: PaymentProviderNotFoundException) {
            null
        } catch (e: PaymentCapabilityUnsupportedException) {
            null
        }

    fun confirm(store: String, accountId: String, claim: StoreClaim): IapResult {
        val gateway = gatewayOf(store) ?: throw PaymentUnavailableException()
        val askedAt = purchases.now()
        val purchase = fetch(gateway, claim) ?: throw IapPurchaseNotFoundException()
        // 구글의 productId 는 어느 API 로 물을지 고르는 힌트일 뿐이다 - 스토어가 말한 상품과 다르면 앱이 보낸 값을 믿지 않는다
        if (claim.productId != null && claim.productId != purchase.productId) throw IapPurchaseNotFoundException()
        if (purchase.familyShared || purchase.quantity > 1) throw IapPurchaseNotEligibleException()
        if (purchase.state == StorePurchaseState.REVOKED) throw IapPurchaseRevokedException()
        val mapped = products.find(store, purchase.productId, purchase.basePlanId)
        val product = mapped?.let { resolve(it, accountId, purchase.kind) }
        if (product == null) {
            log.warn("a store purchase was confirmed for a product that is not in the product map store={} product={}", store, purchase.productId)
            throw IapProductUnknownException()
        }
        if (purchase.state == StorePurchaseState.PENDING) return IapResult.Pending
        val result = purchases.record(gateway, accountId, purchase, product, askedAt)
        return when (result.outcome) {
            StoreRecordOutcome.RECORDED, StoreRecordOutcome.ALREADY_RECORDED ->
                IapResult.Granted(purchase.kind, result.orderId, result.subscriptionId?.let(ledger::findSubscription))
            StoreRecordOutcome.NOT_PURCHASED, StoreRecordOutcome.SUBSCRIPTION_ENDED -> throw IapPurchaseRevokedException()
            StoreRecordOutcome.OWNED_BY_ANOTHER_ACCOUNT -> throw IapPurchaseOwnedElsewhereException()
            StoreRecordOutcome.ALREADY_SUBSCRIBED -> throw PaymentAlreadySubscribedException()
        }
    }

    /** 스토어가 안 받는 때(타임아웃 · 429 · 5xx)는 503 이다 - 앱은 끝내지 않고 다음 실행에 다시 보낸다. 스토어가 모르는 구매만 null. */
    private fun fetch(gateway: PaymentStoreGateway, claim: StoreClaim): StorePurchase? =
        try {
            gateway.fetch(claim)
        } catch (e: ExternalHttpException) {
            log.warn("the store could not confirm a purchase provider={} client={} status={}", gateway.providerId, e.clientName, e.upstreamStatus)
            throw PaymentUnavailableException()
        }

    /**
     * 지도의 한 줄을 장부가 쓰는 상품으로 푼다. 종류가 구매와 다르거나 플랜을 못 찾으면 null(지급하지 않는다).
     * 카탈로그는 주문 이름과 「살 수 있는가」를 보는 데만 쓴다 - 판매가 끝난 상품이어도 **이미 돈을 냈으니 지급한다**(이름만 품목 코드로).
     */
    private fun resolve(line: IapProduct, accountId: String, kind: StorePurchaseKind): StoreProduct? {
        if (line.kind != kind) return null
        if (kind == StorePurchaseKind.SUBSCRIPTION) return plans.getIfAvailable()?.find(checkNotNull(line.plan))?.let(StoreProduct::Subscription)
        val itemType = checkNotNull(line.itemType)
        val itemRef = checkNotNull(line.itemRef)
        val name = try {
            catalog.getIfAvailable()?.quote(PurchaseRequest(accountId, itemType, itemRef, 1, null))?.orderName
        } catch (e: ApplicationException) {
            null
        }
        return StoreProduct.OneTime(itemType, itemRef, name ?: itemRef)
    }
}
