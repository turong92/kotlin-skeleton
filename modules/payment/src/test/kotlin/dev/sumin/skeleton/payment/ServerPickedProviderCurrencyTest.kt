package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * 나라가 정한 결제사도 그 가격의 통화를 받아야 한다 - 안 받으면 결제사를 부르기 전에 `ROUTING_FAILED`(금액을 정하는 카탈로그가 통화별 가격을 갖게 되면 한 나라에 다른 통화 가격 코드가 올 수 있다).
 * 받는 통화를 적지 않은 결제사(Paddle 은 가격이 통화를 정한다)는 거르지 않는다.
 */
class ServerPickedProviderCurrencyTest {
    private val catalog = PaymentCatalog { request ->
        when (request.itemRef) {
            "krw-item" -> PurchaseQuote(PaymentAmount(1_000, "KRW"), "KRW item")
            "usd-item" -> PurchaseQuote(PaymentAmount(300, "USD"), "USD item")
            else -> throw PaymentItemNotPurchasableException()
        }
    }
    private val kit = PaymentLedgerKit(catalog = catalog).also {
        it.plans.plans += SubscriptionPlan("basic-usd", "Basic", PaymentAmount(300, "USD"), "P1M", 1, productCode = "basic")
    }

    private fun create(item: String, provider: String?) =
        kit.orders.create(CreateOrderCommand("acc_1", "item", item, IdempotencyKey("k-$item-$provider"), provider = provider))

    @Test
    fun `a server-picked provider that does not take the currency is refused before it is called, one that lists no currency is not filtered`() {
        assertFailsWith<PaymentRoutingException> { create("usd-item", "scripted") }
        assertEquals(0, kit.gateway.checkoutCalls.size)
        assertEquals(null, kit.store.findOrderByDedupeKey(PaymentOrderIds.dedupeKey("acc_1", IdempotencyKey("k-usd-item-scripted"))), "no order row")

        assertEquals(PaymentAmount(1_000, "KRW"), create("krw-item", "scripted").order.amount)
        assertEquals(PaymentAmount(300, "USD"), create("usd-item", "paddle").order.amount, "the Paddle fake lists no currency - its price decides")
    }

    @Test
    fun `a subscription on a price in a currency the picked provider does not take is refused and leaves no row`() {
        assertFailsWith<PaymentRoutingException> { kit.subscriptions.start("acc_1", "basic-usd", IdempotencyKey("s1"), provider = "scripted") }
        assertNull(kit.store.findLatestSubscription("acc_1"))

        val started = kit.subscriptions.start("acc_1", "basic-usd", IdempotencyKey("s2"), provider = "paddle")
        assertEquals("paddle", started.subscription.provider)
    }
}
