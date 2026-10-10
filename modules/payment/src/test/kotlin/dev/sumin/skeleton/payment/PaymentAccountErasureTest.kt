package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.erasure.AccountTombstone
import dev.sumin.skeleton.common.erasure.ErasureRequest
import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import dev.sumin.skeleton.payment.testing.ScriptedGateway
import dev.sumin.skeleton.payment.web.OrderDetailResponse
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 계정 탈퇴 고리(스펙 §8.2) — 주문 줄은 남고 카드 두 칸만 지워지며 멱등이다. 늦게 `PAID` 가 돼도 카드는 다시 쓰이지 않는다. */
class PaymentAccountErasureTest {
    private fun erasure(kit: PaymentLedgerKit, mode: PaymentProperties.Erasure.AccountIdMode = PaymentProperties.Erasure.AccountIdMode.KEEP) =
        PaymentAccountErasure(kit.store, kit.writer, mode)

    private fun request(account: String) = ErasureRequest(account, AccountTombstone.of(account))

    @Test
    fun `the lines stay, the card columns go, the erased time is stamped, unpaid attempts are canceled and other accounts are untouched`() {
        val kit = PaymentLedgerKit()
        val paid = kit.paid("acc_gone", "k1", "pay_1")
        val unpaid = kit.create("acc_gone", key = "k2").order
        val other = kit.paid("acc_stay", "k3", "pay_3")
        assertEquals("HYUNDAI", kit.order(paid.id).cardIssuer)

        erasure(kit).erase(request("acc_gone"))

        val gone = kit.order(paid.id)
        assertNull(gone.cardIssuer)
        assertNull(gone.cardLast4)
        assertNotNull(gone.accountErasedAt)
        assertEquals("acc_gone", gone.accountId, "KEEP leaves the account id")
        assertEquals(PaymentOrderStatus.PAID, gone.status)
        assertEquals(PaymentOrderStatus.CANCELED, kit.order(unpaid.id).status)
        val before = kit.store.allOrders()
        erasure(kit).erase(request("acc_gone"))
        assertEquals(before, kit.store.allOrders(), "a second erase changes nothing")
        assertEquals("HYUNDAI", kit.order(other.id).cardIssuer)
        assertNull(kit.order(other.id).accountErasedAt)
    }

    @Test
    fun `a confirming order is left alone and when it turns paid later the card columns are not written again`() {
        val kit = PaymentLedgerKit()
        val created = kit.createAuthenticated("acc_gone", paymentKey = "pay_late")
        kit.gateway.script(ScriptedGateway.ConfirmScript.TimeoutNotApplied)
        kit.orders.confirm("acc_gone", created.id, "pay_late", created.amount)
        assertEquals(PaymentOrderStatus.CONFIRMING, kit.order(created.id).status)

        erasure(kit).erase(request("acc_gone"))
        assertEquals(PaymentOrderStatus.CONFIRMING, kit.order(created.id).status)

        kit.gateway.settle("pay_late")
        kit.advance(Duration.ofMinutes(5))
        kit.runTasks()

        val paid = kit.order(created.id)
        assertEquals(PaymentOrderStatus.PAID, paid.status)
        assertNull(paid.cardIssuer)
        assertNull(paid.cardLast4)
        assertNotNull(paid.accountErasedAt)
    }

    @Test
    fun `an open refund request stays for the operator, and the operator can still refund the erased account's order`() {
        val kit = PaymentLedgerKit()
        val paid = kit.paid("acc_gone")
        kit.refunds.request("acc_gone", paid.id, "CUSTOMER_REQUEST", 1000, IdempotencyKey("r1"))
        erasure(kit).erase(request("acc_gone"))

        assertEquals(PaymentRefundStatus.REQUESTED, kit.store.listRefunds(paid.id).single().status)
        val outcome = kit.refunds.approve("operator_1", kit.store.listRefunds(paid.id).single().id)
        assertEquals(PaymentRefundStatus.SUCCEEDED, outcome.refund.status)
    }

    @Test
    fun `TOMBSTONE rewrites the account id on the account's own orders and what it wrote there, and leaves an operator id it left on someone else's order`() {
        val kit = PaymentLedgerKit()
        val own = kit.paid("acc_gone", "k1", "pay_1")
        val others = kit.paid("acc_other", "k2", "pay_2")
        kit.refunds.request("acc_other", others.id, "CUSTOMER_REQUEST", 1000, IdempotencyKey("r-other"))
        val line = kit.store.listRefunds(others.id).single()
        kit.refunds.reject("acc_gone", line.id) // 이 계정이 운영자로서 남의 주문의 요청을 거절했다
        val tombstone = AccountTombstone.of("acc_gone")

        erasure(kit, PaymentProperties.Erasure.AccountIdMode.TOMBSTONE).erase(request("acc_gone"))

        assertEquals(tombstone, kit.order(own.id).accountId)
        assertTrue(kit.store.listEvents(own.id).filter { it.actorId != null }.all { it.actorId == tombstone })
        assertEquals("acc_other", kit.order(others.id).accountId)
        assertEquals("acc_gone", kit.store.findRefund(line.id)!!.decidedBy, "the operator decision on someone else's order is a record of who decided")
    }

    @Test
    fun `the export hook gives the account's payments and refund lines in the response shape, card included`() {
        val kit = PaymentLedgerKit()
        val paid = kit.paid("acc_exp")
        kit.refunds.request("acc_exp", paid.id, "DUPLICATE", null, IdempotencyKey("r1"))
        kit.create("acc_exp", key = "k-unpaid")

        val exported = PaymentAccountDataExporter(PaymentQueries(kit.store, kit.router, kit.writer, kit.properties)).export("acc_exp")

        @Suppress("UNCHECKED_CAST")
        val orders = exported["orders"] as List<OrderDetailResponse>
        assertEquals(1, orders.size, "the unpaid attempt is not exported")
        assertEquals("REQUESTED", orders.single().refunds.single().status)
    }
}
