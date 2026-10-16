package dev.sumin.skeleton.payment.naverpay

import dev.sumin.skeleton.payment.CreateOrderCommand
import dev.sumin.skeleton.payment.IdempotencyKey
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentAmountMismatchException
import dev.sumin.skeleton.payment.PaymentOrder
import dev.sumin.skeleton.payment.PaymentOrderStatus
import dev.sumin.skeleton.payment.PaymentRefundAmountExceededException
import dev.sumin.skeleton.payment.PaymentRefundStatus
import dev.sumin.skeleton.payment.PaymentReviewReason
import dev.sumin.skeleton.payment.naverpay.support.FakeNaverPayServer
import dev.sumin.skeleton.payment.naverpay.support.FakeNaverPayServer.Fail
import dev.sumin.skeleton.payment.naverpay.support.naverProvider
import dev.sumin.skeleton.payment.naverpay.support.payOrderWindow
import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * 진짜 네이버페이 모듈을 메모리 장부에 꽂아(엄격한 가짜 네이버페이 서버와 말한다) 돈 흐름을 끝까지 본다 -
 * 만들기(결제 예약 → 결제창 주소) → 구매자가 예약한 결제창에서 결제(브라우저가 `paymentId` 를 돌려받음) → 승인 → 장부 `PAID` → 환불.
 * 지키는 것: 결제창은 서버가 장부 금액으로 예약해 구매자가 금액을 못 바꿈 · 금액이 다른 승인 요청(요청 변조)은 결제사를 부르기 전에 막힘 · 다른 주문의 결제 번호는 `PAID` 가 되지 않음 · 같은 결제는 두 번 반영되지 않음(재전송 · 응답 유실) ·
 * 남은 금액을 넘는 환불은 장부가 막음.
 */
class NaverPayLedgerTest {
    private val server = FakeNaverPayServer()
    private val provider = naverProvider(server)
    private val kit = PaymentLedgerKit(extraGateways = listOf(provider))

    @AfterTest
    fun tearDown() = server.close()

    private fun order(key: String = "k1"): PaymentOrder =
        kit.orders.create(CreateOrderCommand("acc_1", "cheer", "cheer-3000", IdempotencyKey(key), provider = "naverpay")).order

    private fun applies() = server.requests.count { it.path.endsWith("/apply/payment") }

    @Test
    fun `an order for NaverPay carries no payment id yet, and a confirm with the id the browser got back pays it`() {
        val order = order()
        assertNull(order.providerPaymentId, "the payment id is chosen in the payment window and comes back with the buyer")
        val paymentId = payOrderWindow(server, order.id)

        val paid = kit.orders.confirm("acc_1", order.id, paymentId, order.amount)

        assertEquals(PaymentOrderStatus.PAID, paid.status)
        assertEquals(3000, paid.paidAmount)
        assertEquals("EASY_PAY", paid.method)
        assertEquals(1, applies())
    }

    @Test
    fun `a tampered amount in the confirm never reaches the provider`() {
        val order = order()
        val paymentId = payOrderWindow(server, order.id)

        assertFailsWith<PaymentAmountMismatchException> { kit.orders.confirm("acc_1", order.id, paymentId, PaymentAmount(1, "KRW")) }

        assertEquals(0, applies())
        assertEquals(PaymentOrderStatus.PENDING, kit.order(order.id).status)
    }

    @Test
    fun `the window of an order is reserved with the ledger's amount and order id - the buyer has no amount to lower`() {
        val order = order()

        val reservation = server.reservations().single()
        assertEquals(order.id, reservation.merchantPayKey)
        assertEquals(order.amount.amount, reservation.total)
        val paid = kit.orders.confirm("acc_1", order.id, payOrderWindow(server, order.id), order.amount)
        assertEquals(PaymentOrderStatus.PAID, paid.status)
    }

    @Test
    fun `a payment id that was made for another order is not marked paid either`() {
        val mine = order("k1")
        val other = order("k2")
        val othersPaymentId = payOrderWindow(server, other.id)

        kit.orders.confirm("acc_1", mine.id, othersPaymentId, mine.amount)

        assertEquals(PaymentReviewReason.ORDER_MISMATCH, kit.order(mine.id).reviewReason)
        assertEquals(0, kit.listener.paid.size)
    }

    @Test
    fun `sending the same confirm again after it paid does not apply twice`() {
        val order = order()
        val paymentId = payOrderWindow(server, order.id)
        kit.orders.confirm("acc_1", order.id, paymentId, order.amount)

        val again = kit.orders.confirm("acc_1", order.id, paymentId, order.amount)

        assertEquals(PaymentOrderStatus.PAID, again.status)
        assertEquals(1, applies())
        assertEquals(1, kit.listener.paid.size, "the purchase was delivered once")
    }

    @Test
    fun `a lost apply answer leaves the order confirming, a resend does not call the provider again, and the lookup task settles it as paid once`() {
        val order = order()
        val paymentId = payOrderWindow(server, order.id)
        server.failNext("apply", Fail.LOSE)

        val unsure = kit.orders.confirm("acc_1", order.id, paymentId, order.amount)
        assertEquals(PaymentOrderStatus.CONFIRMING, unsure.status)
        assertEquals(PaymentOrderStatus.CONFIRMING, kit.orders.confirm("acc_1", order.id, paymentId, order.amount).status)
        assertEquals(1, applies(), "the resend did not go to the provider")

        kit.advance(Duration.ofMinutes(3))
        kit.runTasks()
        kit.runTasks() // 지급 작업은 승인을 맞춘 다음 판에 돈다

        assertEquals(PaymentOrderStatus.PAID, kit.order(order.id).status)
        assertEquals(1, applies())
        assertEquals(1, kit.listener.paid.size)
    }

    @Test
    fun `when NaverPay cannot reserve, no order window opens and the order stays unpaid (503)`() {
        server.failNext("reserve", Fail.ERROR)

        assertFailsWith<dev.sumin.skeleton.payment.PaymentUnavailableException> { order("k-down") }

        assertEquals(0, applies())
    }

    @Test
    fun `a buyer who never completed the window moves no money - the order is closed as expired by the expiry task`() {
        val order = order()

        kit.advance(Duration.ofMinutes(31))
        kit.runTasks()

        assertEquals(PaymentOrderStatus.EXPIRED, kit.order(order.id).status)
        assertEquals(0, applies())
    }

    @Test
    fun `the operator refunds a paid order in two parts, a third one over the rest is stopped by the ledger and the provider ends with nothing left`() {
        val order = order()
        val paymentId = payOrderWindow(server, order.id)
        kit.orders.confirm("acc_1", order.id, paymentId, order.amount)

        kit.refunds.refund("op_1", order.id, "OPERATOR", 1000, IdempotencyKey("r1"))
        assertFailsWith<PaymentRefundAmountExceededException> { kit.refunds.refund("op_1", order.id, "OPERATOR", 2001, IdempotencyKey("r2")) }
        val last = kit.refunds.refund("op_1", order.id, "OPERATOR", 2000, IdempotencyKey("r3"))

        assertEquals(PaymentOrderStatus.REFUNDED, last.order.status)
        assertEquals(3000, last.order.refundedAmount)
        assertEquals(PaymentRefundStatus.SUCCEEDED, last.refund.status)
        assertEquals(0, server.paymentOf(paymentId)!!.available)
        assertEquals(listOf(1000L, 2000L), server.paymentOf(paymentId)!!.cancels.map { it.second })
    }
}
