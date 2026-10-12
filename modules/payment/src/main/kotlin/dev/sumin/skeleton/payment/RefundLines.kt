package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.id.SortableIds
import java.time.Instant

/**
 * 환불 줄이 `SUCCEEDED` 가 되는 **유일한 길** — 결제사 응답으로도, 조회 맞추기로도, 결제사 관리 화면의 취소를 발견해도 여기를 지난다.
 * 호출하는 쪽이 주문 줄을 잠그고 다시 읽은 값을 넘긴다. 주문의 `refunded_amount` · 상태를 같은 트랜잭션에서 올리고 회수 작업을 넣는다.
 *
 * 이력은 둘이다: 환불 줄의 것(`REFUND_SUCCEEDED` · 결제사 취소를 발견했으면 `REFUND_SYNCED`, `from/to` 는 환불 줄의 상태)과 주문이 움직인 것(`CONVERGED`,
 * `providerCode=REFUND_LANDED`, `refundId` 포함, `from/to` 는 주문의 상태).
 */
internal class RefundLines(private val writer: PaymentLedgerWriter, private val tasks: PaymentTasks, private val ids: SortableIds = SortableIds.system) {
    /** 결제사 쪽 총액 기준(`paid_amount`, 없으면 청구액)으로 [amount] 를 더 환불해도 넘치지 않는가. */
    fun fits(order: PaymentOrder, amount: PaymentAmount): Boolean =
        amount.normalizedCurrency == order.amount.normalizedCurrency && order.refundedAmount + amount.amount <= baseOf(order)

    /** `PENDING` 줄이 결제사에서 이뤄졌다 — [providerRefundId] 는 그 취소의 거래 키. */
    fun succeed(tx: PaymentLedgerTx, order: PaymentOrder, refund: PaymentRefund, providerRefundId: String?, at: Instant, actor: PaymentActor, actorId: String?): PaymentOrder {
        val done = writer.transitionRefund(tx, refund, PaymentRefundStatus.SUCCEEDED, PaymentEventType.REFUND_SUCCEEDED, actor, actorId) {
            // 접수 표시(PROVIDER_PENDING)는 PENDING 일 때만 뜻이 있다. 다른 표시(RESULT_MISMATCH)는 사람이 본 이력이라 남긴다
            it.copy(providerRefundId = providerRefundId, completedAt = at, failureCode = it.failureCode.takeUnless { code -> code == RefundSettlement.PROVIDER_PENDING })
        }
        return landed(tx, order, done, actor, actorId)
    }

    /** 장부가 모르는 결제사 취소를 `origin=PROVIDER` 줄 하나로 넣는다. 같은 취소는 두 번 들어가지 못한다(`(order_id, provider_refund_id)` 유니크). */
    fun addFromProvider(tx: PaymentLedgerTx, order: PaymentOrder, cancel: ProviderRefund): PaymentOrder {
        val now = writer.now()
        val line = PaymentRefund(
            id = newRefundId(), orderId = order.id, status = PaymentRefundStatus.SUCCEEDED, origin = PaymentRefundOrigin.PROVIDER, amount = cancel.amount,
            reasonCode = PaymentRefundReason.PROVIDER_CONSOLE.name, openKey = null, dedupeKey = null, providerRefundId = cancel.providerRefundId, expectedRemaining = null,
            requestedBy = null, decidedBy = null, failureCode = null, requestedAt = cancel.at, sentAt = null, completedAt = cancel.at, appliedAt = null, createdAt = now, updatedAt = now,
        )
        // 락 안에서 이미 아는 취소는 걸러 넣으므로 여기서 거절되면 버그다 — 던져서 되돌린다
        val stored = checkNotNull(writer.createRefund(tx, line, PaymentEventType.REFUND_SYNCED, PaymentActor.PROVIDER)) { "the provider cancel could not be recorded as a refund line" }
        return landed(tx, order, stored, PaymentActor.PROVIDER, null)
    }

    private fun landed(tx: PaymentLedgerTx, order: PaymentOrder, line: PaymentRefund, actor: PaymentActor, actorId: String?): PaymentOrder {
        val refunded = order.refundedAmount + line.amount.amount
        val to = if (refunded >= baseOf(order)) PaymentOrderStatus.REFUNDED else PaymentOrderStatus.PARTIALLY_REFUNDED
        val changed = writer.transition(tx, order, to, PaymentEventType.CONVERGED, actor, actorId, "REFUND_LANDED", line.id) { it.copy(refundedAmount = refunded) }
        tasks.enqueue(PaymentTask(PaymentTaskTypes.REFUND_APPLY, line.id))
        if (to == PaymentOrderStatus.REFUNDED) closeRequests(tx, order.id)
        return changed
    }

    /** 주문이 전액 환불됐다 — 열려 있던 본인의 요청은 더 물을 것이 없다. 거절도 거둠도 아니라 `WITHDRAWN` + 이유 [ORDER_REFUNDED] 로 닫는다(이력의 행위자는 시스템). */
    private fun closeRequests(tx: PaymentLedgerTx, orderId: String) {
        tx.listRefunds(orderId).filter { it.status == PaymentRefundStatus.REQUESTED }.forEach {
            writer.transitionRefund(tx, it, PaymentRefundStatus.WITHDRAWN, PaymentEventType.REFUND_WITHDRAWN, PaymentActor.SYSTEM, null, ORDER_REFUNDED) { request ->
                request.copy(failureCode = ORDER_REFUNDED, completedAt = writer.now())
            }
        }
    }

    fun newRefundId(): String = ids.newId("rfd_")

    companion object {
        /** 열려 있던 요청 줄의 `failure_code` · 이력의 `provider_code`: 요청이 닫히기 전에 주문이 전액 환불됐다(결제사 관리 화면의 취소 등). */
        const val ORDER_REFUNDED = "ORDER_REFUNDED"

        fun baseOf(order: PaymentOrder): Long = order.paidAmount ?: order.amount.amount

        /** 결제사가 "결제를 받았다"고 말하는 스냅샷인가 — 환불에 대해 말하는 것은 이런 스냅샷뿐이다. */
        fun received(snapshot: PaymentSnapshot): Boolean =
            snapshot.status == ProviderPaymentStatus.PAID || snapshot.status == ProviderPaymentStatus.PARTIALLY_REFUNDED || snapshot.status == ProviderPaymentStatus.REFUNDED

        /** 결제사의 남은 금액이 장부의 남은 금액(`결제액 - 성공한 환불의 합`)과 같은가. 환불 전 확인 · 맞추기 · 정산이 같은 판정을 쓴다. */
        fun remainingAgrees(order: PaymentOrder, snapshot: PaymentSnapshot): Boolean =
            snapshot.remaining.amount == baseOf(order) - order.refundedAmount && snapshot.remaining.normalizedCurrency == order.amount.normalizedCurrency
    }
}
