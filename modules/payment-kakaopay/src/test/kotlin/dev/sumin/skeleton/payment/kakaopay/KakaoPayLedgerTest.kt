package dev.sumin.skeleton.payment.kakaopay

import dev.sumin.skeleton.payment.CreateOrderCommand
import dev.sumin.skeleton.payment.IdempotencyKey
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentAmountMismatchException
import dev.sumin.skeleton.payment.PaymentOrder
import dev.sumin.skeleton.payment.PaymentOrderNotPayableException
import dev.sumin.skeleton.payment.PaymentOrderStatus
import dev.sumin.skeleton.payment.PaymentRefundStatus
import dev.sumin.skeleton.payment.kakaopay.support.FakeKakaoPayServer
import dev.sumin.skeleton.payment.kakaopay.support.FakeKakaoPayServer.Fail
import dev.sumin.skeleton.payment.kakaopay.support.kakaoProvider
import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * 진짜 카카오페이 모듈을 메모리 장부에 꽂아(엄격한 가짜 카카오페이 서버와 말한다) 돈 흐름을 끝까지 본다 -
 * 만들기(`ready` → 거래 번호가 주문에 박힘) → 구매자 인증 → 승인 → 장부 `PAID` → 환불.
 * 지키는 것: 금액이 다른 승인은 결제사를 부르기 전에 막힘 · 같은 결제는 두 번 반영되지 않음(재전송 · 응답 유실) · 바꿔치기한 거래 번호는 막힘 · 남은 금액을 넘는 환불은 장부가 막음.
 */
class KakaoPayLedgerTest {
    private val server = FakeKakaoPayServer()
    private val provider = kakaoProvider(server)
    private val kit = PaymentLedgerKit(extraGateways = listOf(provider))

    @AfterTest
    fun tearDown() = server.close()

    private fun order(key: String = "k1"): PaymentOrder =
        kit.orders.create(CreateOrderCommand("acc_1", "cheer", "cheer-3000", IdempotencyKey(key), provider = "kakaopay")).order

    private fun approvals() = server.requests.count { it.path.endsWith("/approve") }

    /** 구매자가 카카오톡에서 인증을 마치고 돌아온 상태 - (거래 번호, 승인 토큰). */
    private fun buyerReturns(order: PaymentOrder): Pair<String, String> {
        val tid = checkNotNull(order.providerPaymentId) { "the order must carry the tid the provider fixed" }
        return tid to server.userApproves(tid)
    }

    @Test
    fun `an order for KakaoPay carries the tid the provider fixed, and a confirm with the buyer's token pays it`() {
        val order = order()
        val tid = checkNotNull(order.providerPaymentId)
        assertEquals(order.id, server.paymentOf(tid)!!.partnerOrderId)
        val token = server.userApproves(tid)

        val paid = kit.orders.confirm("acc_1", order.id, tid, order.amount, token)

        assertEquals(PaymentOrderStatus.PAID, paid.status)
        assertEquals(3000, paid.paidAmount)
        assertEquals("EASY_PAY", paid.method)
        assertEquals(1, approvals())
        assertEquals("acc_1", server.paymentOf(tid)!!.partnerUserId)
    }

    @Test
    fun `a tampered amount never reaches the provider - and a confirm with another tid than the order's neither`() {
        val order = order()
        val (tid, token) = buyerReturns(order)

        assertFailsWith<PaymentAmountMismatchException> { kit.orders.confirm("acc_1", order.id, tid, PaymentAmount(1, "KRW"), token) }
        assertFailsWith<PaymentOrderNotPayableException> { kit.orders.confirm("acc_1", order.id, "T0000000000000000999", order.amount, token) }

        assertEquals(0, approvals())
        assertEquals(PaymentOrderStatus.PENDING, kit.order(order.id).status)
    }

    @Test
    fun `sending the same confirm again after it paid does not approve twice`() {
        val order = order()
        val (tid, token) = buyerReturns(order)
        kit.orders.confirm("acc_1", order.id, tid, order.amount, token)

        val again = kit.orders.confirm("acc_1", order.id, tid, order.amount, token)

        assertEquals(PaymentOrderStatus.PAID, again.status)
        assertEquals(1, approvals())
        assertEquals(1, kit.listener.paid.size, "the purchase was delivered once")
    }

    @Test
    fun `a lost approve answer leaves the order confirming, a resend does not call the provider again, and the lookup task settles it as paid once`() {
        val order = order()
        val (tid, token) = buyerReturns(order)
        server.failNext("approve", Fail.LOSE)

        val unsure = kit.orders.confirm("acc_1", order.id, tid, order.amount, token)
        assertEquals(PaymentOrderStatus.CONFIRMING, unsure.status)
        assertEquals(PaymentOrderStatus.CONFIRMING, kit.orders.confirm("acc_1", order.id, tid, order.amount, token).status)
        assertEquals(1, approvals(), "the resend did not go to the provider")

        kit.advance(Duration.ofMinutes(3))
        kit.runTasks()
        kit.runTasks() // 지급 작업은 승인을 맞춘 다음 판에 돈다

        assertEquals(PaymentOrderStatus.PAID, kit.order(order.id).status)
        assertEquals(1, approvals())
        assertEquals(1, kit.listener.paid.size)
    }

    @Test
    fun `a buyer who never approved moves no money - the order is closed as expired by the expiry task`() {
        val order = order()
        assertNotNull(order.providerPaymentId)

        kit.advance(Duration.ofMinutes(31))
        kit.runTasks()

        assertEquals(PaymentOrderStatus.EXPIRED, kit.order(order.id).status)
        assertEquals(0, approvals())
    }

    @Test
    fun `the operator refunds a paid order in two parts, a third one over the rest is stopped by the ledger and the provider ends with nothing left`() {
        val order = order()
        val (tid, token) = buyerReturns(order)
        kit.orders.confirm("acc_1", order.id, tid, order.amount, token)

        kit.refunds.refund("op_1", order.id, "OPERATOR", 1000, IdempotencyKey("r1"))
        assertFailsWith<dev.sumin.skeleton.payment.PaymentRefundAmountExceededException> { kit.refunds.refund("op_1", order.id, "OPERATOR", 2001, IdempotencyKey("r2")) }
        val last = kit.refunds.refund("op_1", order.id, "OPERATOR", 2000, IdempotencyKey("r3"))

        assertEquals(PaymentOrderStatus.REFUNDED, last.order.status)
        assertEquals(3000, last.order.refundedAmount)
        assertEquals(PaymentRefundStatus.SUCCEEDED, last.refund.status)
        assertEquals(0, server.paymentOf(tid)!!.available)
        assertEquals(listOf(1000L, 2000L), server.paymentOf(tid)!!.cancels.map { it.second })
    }
}
