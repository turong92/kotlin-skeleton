package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * 결제사를 꺼도(`providers.<id>.enabled=false`) 결제사가 하나뿐이면 라우터의 단일 결제사 지름길이 그것을 돌려준다(그 지름길은 호환 때문에 둔다 — 승인 경로가 `router.gateway` 로 막는다).
 * 그 틈으로 결제창이 열리면 고객이 인증까지 마친 뒤 승인이 `PROVIDER_NOT_FOUND` 로 막힌다. 결제창을 열기 전에 막혀야 한다.
 */
class DisabledSoleProviderCheckoutTest {
    private fun disabledKit() = PaymentLedgerKit(
        properties = PaymentProperties(
            providers = mapOf("scripted" to PaymentProperties.Provider(enabled = false)),
            webhook = PaymentProperties.Webhook(pathToken = PaymentLedgerKit.WEBHOOK_TOKEN),
        ),
    )

    @Test
    fun `a disabled sole provider is not an openable checkout - config says unavailable`() {
        val kit = disabledKit()

        assertNull(kit.orders.openableCheckout(null, null))
        assertNull(kit.orders.openableCheckout(PaymentAmount(3000, "KRW"), "KR"))
    }

    @Test
    fun `creating an order with a disabled sole provider is unavailable and writes no order, no task`() {
        val kit = disabledKit()

        assertFailsWith<PaymentUnavailableException> { kit.create(key = "k") }

        assertNull(kit.store.allOrders().firstOrNull())
        assertNull(kit.tasks.all().firstOrNull())
        assertNull(kit.gateway.checkoutCalls.firstOrNull())
    }

}
