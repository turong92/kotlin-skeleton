package dev.sumin.skeleton.payment.jdbc

import dev.sumin.skeleton.payment.IdempotencyKey
import dev.sumin.skeleton.payment.PaymentOrderNotPayableException
import dev.sumin.skeleton.payment.PaymentOrderStatus
import dev.sumin.skeleton.payment.PaymentRefundStatus
import dev.sumin.skeleton.payment.PaymentTaskRetryException
import dev.sumin.skeleton.payment.PaymentTaskRun
import dev.sumin.skeleton.payment.PaymentTaskTypes
import dev.sumin.skeleton.payment.testing.ScriptedGateway.RefundScript
import java.time.Duration
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * **어긋난 시계 실험**(B10) — 백엔드마다 시계가 다르면(한 대가 1분 빠르다) 무엇이 달라지는지 **잰 대로** 적어 둔다. 이 시험은 "시계가 어긋나도 괜찮다"를 증명하지 않는다:
 * 마감 · 정산 대기 같은 시각 판단은 **판단하는 백엔드의 시계**로 하므로, 빠른 한 대는 다른 대가 아직 허용하는 것을 거절하고 정산 대기를 일찍 끝낸다.
 * 어긋난 시계에서도 지켜지는 것(끝 상태가 하나 · 장부와 결제사가 결국 맞는다)과 지켜지지 않는 것(시각 판단 자체)을 가른다. 운영 쪽 대책은 인스턴스 시계를 NTP 로 맞추는 것이다.
 */
class MultiInstanceClockSkewDbTest {
    @BeforeTest
    fun clean() = PaymentDb.clean()

    private val fast = Duration.ofSeconds(60)

    @Test
    fun `an instance whose clock is a minute fast refuses a confirm that the others still accept - the ledger still ends with one answer`() {
        LedgerCluster().use { c ->
            val order = c.instances[0].create("skew-confirm").order
            val truth = order.checkoutExpiresAt.minusSeconds(30) // 진짜 시각: 마감 30초 전 — 아직 결제할 수 있다
            c.setClocks(truth)
            c.instances[0].time.set(truth.plus(fast)) // be-0 은 마감 30초 뒤라고 믿는다

            assertFailsWith<PaymentOrderNotPayableException>("the fast instance judges the deadline by its own clock") {
                c.instances[0].orders.confirm("acc_1", order.id, "pay_skew", order.amount)
            }
            assertEquals(0, c.provider.confirmCalls.size)
            assertEquals(PaymentOrderStatus.PENDING, c.instances[1].order(order.id).status, "refusing wrote nothing")

            val accepted = c.instances[1].orders.confirm("acc_1", order.id, "pay_skew", order.amount) // 같은 요청, 다른 백엔드
            assertEquals(PaymentOrderStatus.PAID, accepted.status)
            assertEquals(1, c.provider.confirmCalls.size)
            assertEquals(1, c.listenersPaid)
        }
    }

    @Test
    fun `a worker whose clock is a minute fast expires an unpaid order before its deadline - a confirm on a correct instance is then refused, never both states`() {
        LedgerCluster().use { c ->
            val order = c.instances[0].create("skew-expire").order
            val truth = order.checkoutExpiresAt.minusSeconds(30) // 진짜 시각: 마감 30초 전
            c.setClocks(truth)
            val trueQueueSkew = Duration.between(Instant.now(), truth) // 큐 시계(실제 시각 + 어긋남)를 "진짜 시각 = truth" 로 맞춘다
            c.instances[0].queueSkew = trueQueueSkew.plus(fast) // be-0 의 큐 시계만 1분 빠르다
            c.instances[1].queueSkew = trueQueueSkew

            assertEquals(0, c.instances[1].worker.pollOnce(), "a correct worker does not see the expiry task due yet")
            assertEquals(PaymentOrderStatus.PENDING, c.instances[2].order(order.id).status)

            assertEquals(1, c.instances[0].worker.pollOnce(), "the fast worker sees it due 30 seconds early")
            assertEquals(PaymentOrderStatus.EXPIRED, c.instances[2].order(order.id).status, "closed as unpaid before the real deadline")
            assertFailsWith<PaymentOrderNotPayableException> { c.instances[1].orders.confirm("acc_1", order.id, "pay_skew", order.amount) }
            assertEquals(0, c.provider.confirmCalls.size)
            assertEquals(0, c.listenersPaid)
        }
    }

    @Test
    fun `a settle task run by an instance whose clock is a minute fast closes a pending refund before the settle delay - the ledger and the provider still agree after the late refund is synced`() {
        LedgerCluster().use { c ->
            val order = c.paid("skew-refund", "pay_skew")
            c.provider.scriptRefund(RefundScript.TimeoutAppliedLater) // 응답은 못 받았고 결제사는 아직 적용 전
            val sent = c.instances[0].refunds.refund("op_1", order.id, "OPERATOR", 1000, IdempotencyKey("skew-r")).refund
            assertEquals(PaymentRefundStatus.PENDING, sent.status)
            val settleDelay = c.properties.refund.settleDelay
            val run = PaymentTaskRun(PaymentTaskTypes.REFUND_RECONCILE, sent.id, 1, 12)

            // 진짜 경과: 정산 대기(2분)보다 짧은 70초
            val trueNow = sent.sentAt!!.plusSeconds(70)
            c.instances[1].time.set(trueNow)
            assertFailsWith<PaymentTaskRetryException>("a correct instance waits out the settle delay") { c.instances[1].refundReconcile.handle(run) }
            assertEquals(PaymentRefundStatus.PENDING, c.instances[2].store.findRefund(sent.id)!!.status)

            c.instances[2].time.set(trueNow.plus(fast)) // 1분 빠른 be-2: 정산 대기가 지났다고 본다
            assertEquals(true, trueNow.plus(fast) >= sent.sentAt!!.plus(settleDelay))
            c.instances[2].refundReconcile.handle(run)
            val closed = c.instances[0].store.findRefund(sent.id)!!
            assertEquals(PaymentRefundStatus.FAILED, closed.status, "closed as NOT_APPLIED about 50 seconds before the settle delay ends")
            assertEquals("NOT_APPLIED", closed.failureCode)

            // 결제사가 뒤늦게 적용했다 — 장부는 아직 모르고, 운영자의 「결제사와 맞추기」가 결제사 취소로 잡는다
            c.provider.applyLate()
            assertEquals(0, c.instances[0].order(order.id).refundedAmount, "the ledger does not know yet")
            c.instances[1].refunds.sync("op_1", order.id)
            assertEquals(1000, c.provider.payment("pay_skew")!!.refundedTotal)
            assertEquals(c.provider.payment("pay_skew")!!.refundedTotal, c.instances[0].order(order.id).refundedAmount, "after the sync the ledger and the provider agree")
            assertNull(c.instances[0].order(order.id).reviewReason, "nothing is left flagged")
        }
    }
}
