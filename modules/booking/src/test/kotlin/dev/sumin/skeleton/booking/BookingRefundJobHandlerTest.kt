package dev.sumin.skeleton.booking

import dev.sumin.skeleton.jobqueue.jdbc.Job
import dev.sumin.skeleton.jobqueue.jdbc.JobStatus
import dev.sumin.skeleton.payment.IdempotencyKey
import dev.sumin.skeleton.payment.PaymentOrderNotFoundException
import dev.sumin.skeleton.payment.PaymentOrderStatus
import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `booking-refund` 처리기의 돈 경로(리뷰 수정 I2) - 진짜 환불 로직(메모리 장부, 가짜 결제사)에 대고:
 * 정상 경로(결제사를 안 거친 주문 · 이미 전액 환불된 주문)가 작업 실패로 끝나 DEAD 경보가 나면 안 되고, 진짜 실패는 그대로 실패여야 한다.
 */
class BookingRefundJobHandlerTest {
    private val kit = PaymentLedgerKit().also { it.exemptAccounts += "acc_free" }
    private val handler = BookingRefundJobHandler(kit.refunds)

    private fun job(orderId: String, reason: String = "DUPLICATE") =
        Job(1, BOOKING_REFUND_JOB, """{"orderId":"$orderId","reason":"$reason"}""", JobStatus.RUNNING, 1, 5, Instant.now(), null, null, null, Instant.now(), Instant.now())

    @Test
    fun `an order that never went through a provider has nothing to refund - the job finishes instead of dying`() {
        val order = kit.create(accountId = "acc_free").order
        handler.handle(job(order.id))
        assertEquals(0, kit.gateway.refundCalls.size)
    }

    @Test
    fun `a paid order is refunded once however many times the job runs, and an order refunded another way finishes quietly`() {
        val order = kit.paid()
        handler.handle(job(order.id, "NOT_DELIVERED"))
        handler.handle(job(order.id, "NOT_DELIVERED"))
        assertEquals(1, kit.gateway.refundCalls.size)
        assertEquals(PaymentOrderStatus.REFUNDED, kit.order(order.id).status)

        val other = kit.paid(key = "key-2", paymentKey = "pay_2")
        kit.refunds.refund("op_1", other.id, "OPERATOR", null, IdempotencyKey("by-hand"))
        handler.handle(job(other.id))   // 다른 길로 이미 전액 환불됐다
        assertEquals(2, kit.gateway.refundCalls.size)
    }

    @Test
    fun `a real failure is still a failure - an unknown order fails the job`() {
        assertFailsWith<PaymentOrderNotFoundException> { handler.handle(job("ord_missing")) }
    }
}
