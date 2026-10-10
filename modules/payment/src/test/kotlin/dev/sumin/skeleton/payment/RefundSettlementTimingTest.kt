package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import dev.sumin.skeleton.payment.testing.ScriptedGateway.RefundScript
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `NOT_APPLIED` 는 **스냅샷을 얻은 시각**으로 `settle-delay` 를 잰다 — 조회를 먼저 하고 락을 기다리다 기한 뒤에 판정하게 돼도, 보내기 전에 얻은 낡은 스냅샷으로 "안 나갔다"고 닫지 않는다
 * (닫으면 운영자가 다시 눌러 이중 환불이 된다).
 */
class RefundSettlementTimingTest {
    private val op = "op_1"
    private val settleDelay = Duration.ofMinutes(2)

    private fun timedOutLater(kit: PaymentLedgerKit): PaymentRefund {
        val order = kit.paid()
        kit.gateway.scriptRefund(RefundScript.TimeoutAppliedLater)
        return kit.refunds.refund(op, order.id, "OPERATOR", 1000, IdempotencyKey("k-1")).refund
    }

    @Test
    fun `a snapshot taken before the delay passed is not turned into not-applied by a late judgement`() {
        val kit = PaymentLedgerKit()
        val line = timedOutLater(kit)
        val lookedAt = kit.time.now()
        val snapshot = kit.gateway.lookup(PaymentLookupQuery("pay_1", null))!! // 보낸 직후의 스냅샷 - 아직 적용 전
        kit.gateway.applyLate() // 그 사이 결제사가 요청을 처리했다
        kit.advance(settleDelay.plusSeconds(30)) // 락을 기다리느라 판정이 기한 뒤가 됐다

        val result = kit.store.inTransaction { tx ->
            RefundSettlement(kit.convergence, kit.writer).settle(
                tx, tx.lockOrder(line.orderId)!!, line.id, snapshot, lookedAt,
                ConvergeContext(PaymentMode.TEST, ConvergeSource.REFUND), PaymentProperties.Refund(settleDelay = settleDelay),
            )
        }

        assertEquals(RefundSettlement.Result.WAIT, result)
        assertEquals(PaymentRefundStatus.PENDING, kit.store.findRefund(line.id)!!.status, "the stale snapshot says nothing about a request that landed after it")
        assertNull(kit.order(line.orderId).reviewReason)
    }

    @Test
    fun `a manual sync whose lookup ran before the delay and whose lock came after it leaves the line open and the next sync finds the refund`() {
        val kit = PaymentLedgerKit()
        val line = timedOutLater(kit)
        kit.advance(settleDelay.minusSeconds(10))
        kit.gateway.afterLookup = { // 조회는 기한 10초 전에 끝났고, 주문 락을 얻는 사이 시간이 지나고 결제사가 처리했다
            kit.advance(Duration.ofSeconds(60))
            kit.gateway.applyLate()
            kit.gateway.afterLookup = null
        }

        kit.refunds.sync(op, line.orderId)

        assertEquals(PaymentRefundStatus.PENDING, kit.store.findRefund(line.id)!!.status)
        assertEquals(emptyList(), kit.store.listRefunds(line.orderId).filter { it.status == PaymentRefundStatus.FAILED })
        val synced = kit.refunds.sync(op, line.orderId)
        assertEquals(PaymentRefundStatus.SUCCEEDED, kit.store.findRefund(line.id)!!.status)
        assertEquals(1000, synced.refundedAmount)
        assertEquals(1, kit.store.listRefunds(line.orderId).size, "no second line for the same cancel")
    }

    /** 웹훅이 하듯 지금 상태로 장부만 맞춘다 - 정산(줄 닫기)은 하지 않는다. */
    private fun absorbWithoutSettling(kit: PaymentLedgerKit, orderId: String) {
        val fresh = kit.gateway.lookup(PaymentLookupQuery("pay_1", null))!!
        kit.store.inTransaction { tx -> kit.convergence.converge(tx, tx.lockOrder(orderId)!!, fresh, ConvergeContext(PaymentMode.TEST, ConvergeSource.WEBHOOK)) }
    }

    @Test
    fun `a snapshot older than another cancel entering the ledger neither flags nor fails the settlement - it asks for a retry`() {
        val kit = PaymentLedgerKit()
        val line = timedOutLater(kit)
        kit.advance(settleDelay)
        val lookedAt = kit.time.now()
        val snapshot = kit.gateway.lookup(PaymentLookupQuery("pay_1", null))!! // 다른 취소가 장부에 들어오기 전에 얻은 스냅샷
        kit.advance(Duration.ofSeconds(5))
        kit.gateway.consoleCancel("pay_1", 500)
        absorbWithoutSettling(kit, line.orderId) // 정산 조회와 락 사이에 다른 취소가 장부에 들어왔다
        assertEquals(500, kit.order(line.orderId).refundedAmount)
        assertNull(kit.order(line.orderId).reviewReason)

        val result = kit.store.inTransaction { tx ->
            RefundSettlement(kit.convergence, kit.writer).settle(
                tx, tx.lockOrder(line.orderId)!!, line.id, snapshot, lookedAt,
                ConvergeContext(PaymentMode.TEST, ConvergeSource.REFUND), PaymentProperties.Refund(settleDelay = settleDelay),
            )
        }

        assertEquals(RefundSettlement.Result.STALE_SNAPSHOT, result)
        assertNull(kit.order(line.orderId).reviewReason, "no false mismatch")
        assertEquals(PaymentRefundStatus.PENDING, kit.store.findRefund(line.id)!!.status)
    }

}
