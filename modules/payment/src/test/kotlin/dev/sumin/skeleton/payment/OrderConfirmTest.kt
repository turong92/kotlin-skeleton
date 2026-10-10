package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import dev.sumin.skeleton.payment.testing.ScriptedGateway.ConfirmScript
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrderConfirmTest {
    @Test
    fun `a confirm claims, calls the provider once with the order's own amount and idempotency key, and marks the order paid`() {
        val kit = PaymentLedgerKit()
        val created = kit.createAuthenticated()

        val paid = kit.orders.confirm("acc_1", created.id, "pay_1", PaymentAmount(3000, "KRW"))

        assertEquals(PaymentOrderStatus.PAID, paid.status)
        assertEquals(3000, paid.paidAmount)
        assertEquals("pay_1", paid.providerPaymentId)
        val call = kit.gateway.confirmCalls.single()
        assertEquals(PaymentAmount(3000, "KRW"), call.amount)
        assertEquals(IdempotencyKey(created.id), call.idempotencyKey)
        assertEquals("pay_1", call.providerPaymentId)
        assertEquals(1, kit.tasks.all(PaymentTaskTypes.ORDER_RECONCILE).size, "the safety net is queued with the claim")
    }

    @Test
    fun `sixteen simultaneous confirms of one order call the provider exactly once`() {
        val kit = PaymentLedgerKit()
        val created = kit.createAuthenticated()
        kit.gateway.duringConfirm = { Thread.sleep(50) } // 선점이 커밋되기 전에 다른 호출이 끼어들 틈을 넓힌다
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(16)
        val results = try {
            val futures = (1..16).map {
                pool.submit<Result<PaymentOrder>> {
                    start.await()
                    runCatching { kit.orders.confirm("acc_1", created.id, "pay_1", created.amount) }
                }
            }
            start.countDown()
            futures.map { it.get(15, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(1, kit.gateway.confirmCalls.size, "the provider was called once")
        assertTrue(results.all { it.isSuccess }, results.mapNotNull { it.exceptionOrNull() }.toString())
        assertEquals(PaymentOrderStatus.PAID, kit.order(created.id).status)
        assertEquals(1, kit.listener.paid.size, "and the purchase was delivered once")
    }

    @Test
    fun `a tampered amount never reaches the provider`() {
        val kit = PaymentLedgerKit()
        val created = kit.createAuthenticated()
        val before = kit.order(created.id)

        assertFailsWith<PaymentAmountMismatchException> { kit.orders.confirm("acc_1", created.id, "pay_1", PaymentAmount(1, "KRW")) }
        assertFailsWith<PaymentAmountMismatchException> { kit.orders.confirm("acc_1", created.id, "pay_1", PaymentAmount(3000, "USD")) }

        assertEquals(0, kit.gateway.confirmCalls.size)
        assertEquals(before, kit.order(created.id), "the row did not move")
    }

    @Test
    fun `a provider total that differs from the order is not marked paid`() {
        val kit = PaymentLedgerKit()
        val created = kit.createAuthenticated()
        kit.gateway.script(ConfirmScript.ApproveWithTotal(PaymentAmount(2000, "KRW")))

        val result = kit.orders.confirm("acc_1", created.id, "pay_1", created.amount)

        assertEquals(PaymentOrderStatus.CONFIRMING, result.status)
        assertEquals(PaymentReviewReason.AMOUNT_MISMATCH, result.reviewReason)
        assertNull(result.paidAmount)
        assertEquals(0, kit.listener.paid.size)
    }

    @Test
    fun `another account's order is indistinguishable from a missing one`() {
        val kit = PaymentLedgerKit()
        val mine = kit.createAuthenticated(accountId = "acc_1")

        val foreign = assertFailsWith<PaymentOrderNotFoundException> { kit.orders.confirm("acc_2", mine.id, "pay_1", mine.amount) }
        val missing = assertFailsWith<PaymentOrderNotFoundException> { kit.orders.confirm("acc_2", "ord_" + "0".repeat(32), "pay_1", mine.amount) }

        assertEquals(missing.message, foreign.message)
        assertFailsWith<PaymentOrderNotFoundException> { kit.orders.get("acc_2", mine.id) }
        assertFailsWith<PaymentOrderNotFoundException> { kit.orders.abandon("acc_2", mine.id) }
        assertEquals(0, kit.gateway.confirmCalls.size)
        assertEquals(PaymentOrderStatus.PENDING, kit.order(mine.id).status)
    }

    @Test
    fun `an order created in test mode is refused under a live key`() {
        val kit = PaymentLedgerKit()
        val created = kit.createAuthenticated()
        kit.gateway.mode = PaymentMode.LIVE

        assertFailsWith<PaymentModeMismatchException> { kit.orders.confirm("acc_1", created.id, "pay_1", created.amount) }

        assertEquals(0, kit.gateway.confirmCalls.size)
        assertEquals(PaymentOrderStatus.PENDING, kit.order(created.id).status)
    }

    @Test
    fun `a definitive refusal closes the order as failed with the provider's code and a later confirm cannot reopen it`() {
        val kit = PaymentLedgerKit()
        val created = kit.createAuthenticated()
        kit.gateway.script(ConfirmScript.Decline("REJECT_CARD_COMPANY"))

        val declined = assertFailsWith<PaymentDeclinedException> { kit.orders.confirm("acc_1", created.id, "pay_1", created.amount) }

        assertEquals("REJECT_CARD_COMPANY", declined.providerCode)
        assertEquals(PaymentOrderStatus.FAILED, kit.order(created.id).status)
        assertEquals("REJECT_CARD_COMPANY", kit.order(created.id).failureCode)
        assertFailsWith<PaymentOrderNotPayableException> { kit.orders.confirm("acc_1", created.id, "pay_2", created.amount) }
        assertEquals(1, kit.gateway.confirmCalls.size, "the provider was not asked again")
    }

    @Test
    fun `a checkout past its deadline cannot be confirmed`() {
        val kit = PaymentLedgerKit()
        val created = kit.createAuthenticated()
        kit.advance(Duration.ofMinutes(31))

        assertFailsWith<PaymentOrderNotPayableException> { kit.orders.confirm("acc_1", created.id, "pay_1", created.amount) }

        assertEquals(0, kit.gateway.confirmCalls.size)
    }

    @Test
    fun `a provider payment id that already belongs to another order is refused and the provider is not called`() {
        val kit = PaymentLedgerKit()
        val first = kit.createAuthenticated(key = "k1", paymentKey = "pay_1")
        val second = kit.createAuthenticated(key = "k2", paymentKey = "pay_2")
        kit.orders.confirm("acc_1", first.id, "pay_1", first.amount)

        assertFailsWith<PaymentOrderNotPayableException> { kit.orders.confirm("acc_1", second.id, "pay_1", second.amount) }

        assertEquals(1, kit.gateway.confirmCalls.size)
        assertEquals(PaymentOrderStatus.PENDING, kit.order(second.id).status)
    }
}
