package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import kotlin.test.Test
import kotlin.test.assertEquals

/** 승인 응답이 카드 · 승인 시각을 싣는 결제사는 승인 직후 조회를 한 번 더 받지 않는다. 싣지 않는 결제사는 그대로 한 번 묻는다. */
class OrderConfirmDetailsTest {
    @Test
    fun `a confirm answer that carries the card and the approval time needs no second lookup`() {
        val kit = PaymentLedgerKit()
        kit.gateway.confirmCarriesDetails = true
        val created = kit.createAuthenticated()

        val paid = kit.orders.confirm("acc_1", created.id, "pay_1", created.amount)

        assertEquals(0, kit.gateway.lookupCalls.size, "the confirm answer already had the card and the time")
        assertEquals(PaymentOrderStatus.PAID, paid.status)
        assertEquals("HYUNDAI", paid.cardIssuer)
        assertEquals("1234", paid.cardLast4)
        assertEquals(kit.time.now(), paid.paidAt)
    }

    @Test
    fun `a confirm answer without the card still asks the provider once`() {
        val kit = PaymentLedgerKit()
        val created = kit.createAuthenticated()

        val paid = kit.orders.confirm("acc_1", created.id, "pay_1", created.amount)

        assertEquals(1, kit.gateway.lookupCalls.size)
        assertEquals("HYUNDAI", paid.cardIssuer)
    }
}
