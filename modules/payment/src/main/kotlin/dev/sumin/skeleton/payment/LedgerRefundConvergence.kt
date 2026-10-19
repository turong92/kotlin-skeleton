package dev.sumin.skeleton.payment

import org.slf4j.LoggerFactory

/**
 * 스펙 §6.1 의 4 · 5 — 결제사의 취소 목록과 장부의 환불 줄을 맞춘다. [PaymentConvergence] 가 결제 쪽을 맞춘 뒤 부른다(주문 줄 락 안, 몇 번 불려도 결과가 같다).
 *
 * **결제를 받았다고 말하는 스냅샷만 읽는다**(`PAID` · `PARTIALLY_REFUNDED` · `REFUNDED`) — 실패 · 진행 중 스냅샷의 금액은 환불에 대해 아무것도 말하지 않는다.
 * 4. 장부에 없는 결제사 취소: 금액이 같은 `PENDING` 환불(결제사 번호가 아직 없는 것)이 있으면 그 줄을 `SUCCEEDED`, 없으면 `origin=PROVIDER` 줄을 새로.
 *    넘치게 되는 취소(합이 결제액 초과 · 통화가 다름)는 반영하지 않고 `REFUND_MISMATCH`.
 * 5. 끝으로 `결제액 - 성공한 환불의 합` 이 결제사의 남은 금액과 다르면 `REFUND_MISMATCH` — 단, 스냅샷을 얻은 **뒤에** 바뀐 환불 줄이 있으면 스냅샷이 낡았을 수 있어 건너뛴다.
 *    같으면 앞서 찍힌 `REFUND_MISMATCH` 는 풀리지만 **풀리지 않은 줄을 화면에서 지우지는 않는다**: 사람이 누른 「결제사와 맞추기」([ConvergeSource.SYNC])이거나,
 *    그 주문에 `PENDING` 환불 줄이 없고 결제사가 요청과 다른 금액을 취소한 표시([RefundSettlement.RESULT_MISMATCH])를 단 줄도 늦게 도착한 성공 응답의 표시([RefundSettlement.LATE_RESULT])를 단 줄도 없을 때만 푼다.
 *    **단 결제사가 접수했다고 답한 줄([RefundSettlement.PROVIDER_PENDING])이 `PENDING` 으로 남아 있으면 사람이 누른 맞추기도 풀지 않는다**(그 줄의 출구는 기한 뒤 `sync` 의 `NOT_APPLIED`).
 *
 * 결제사의 취소 목록은 먼저 걸러 읽는다: 거래 키는 [LedgerValues.code](응답 경로와 같은 함수)를 통과하는 것만(앞뒤 공백은 떼서 저장), 같은 키는 하나, 금액 0 인 취소는 버린다.
 * 버려진 것이 있으면 로그 한 줄(값은 싣지 않는다) — 장부가 못 받은 취소는 남은 금액 대조에서 `REFUND_MISMATCH` 로 드러난다.
 */
class LedgerRefundConvergence(
    private val writer: PaymentLedgerWriter,
    tasks: PaymentTasks,
    review: PaymentReviewNotifier = PaymentReviewNotifier.NONE,
) : PaymentRefundConvergence {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lines = RefundLines(writer, tasks)
    private val flags = PaymentReviewFlags(writer, review)
    private val supply = SupplyWork(tasks)

    override fun converge(tx: PaymentLedgerTx, order: PaymentOrder, snapshot: PaymentSnapshot, context: ConvergeContext): PaymentOrder {
        if (!RefundLines.received(snapshot) || !order.status.isPaidFamily) return order
        var current = order
        val existing = tx.listRefunds(order.id)
        val staleSnapshot = context.snapshotAt?.let { at -> existing.any { !it.updatedAt.isBefore(at) } } ?: false
        val known = existing.mapNotNull { it.providerRefundId }.toMutableSet()
        var unmatchedPending = existing.firstOrNull { it.status == PaymentRefundStatus.PENDING && it.providerRefundId == null }
        // 결제사가 접수할 때 거래 번호를 준 우리 줄(Paddle 의 검토 중 환불) - 번호가 같은 취소가 이뤄졌다면 그 줄이 이뤄진 것이다
        val numbered = existing.filter { it.status == PaymentRefundStatus.PENDING && it.providerRefundId != null }.associateBy { it.providerRefundId }
        for (cancel in usable(order.id, snapshot.refunds).filter { it.providerRefundId !in known || it.providerRefundId in numbered }.sortedBy { it.at }) {
            if (!lines.fits(current, cancel.amount)) return flags.flag(tx, current, PaymentReviewReason.REFUND_MISMATCH, context)
            numbered[cancel.providerRefundId]?.let { own ->
                if (!own.amount.sameAs(cancel.amount)) return flags.flag(tx, current, PaymentReviewReason.REFUND_MISMATCH, context)
                current = lines.succeed(tx, current, own, cancel.providerRefundId, cancel.at, context.actor, context.actorId)
                continue
            }
            val match = unmatchedPending?.takeIf { it.amount.sameAs(cancel.amount) }
            current = if (match != null) {
                unmatchedPending = null
                lines.succeed(tx, current, match, cancel.providerRefundId, cancel.at, context.actor, context.actorId)
            } else {
                lines.addFromProvider(tx, current, cancel)
            }
            known += cancel.providerRefundId
        }
        return checkRemaining(tx, current, snapshot, context, staleSnapshot, SupplyWork.unapplied(existing))
    }

    private fun usable(orderId: String, cancels: List<ProviderRefund>): List<ProviderRefund> {
        val usable = cancels
            .mapNotNull { cancel -> LedgerValues.code(cancel.providerRefundId)?.let { cancel.copy(providerRefundId = it) } }
            .distinctBy { it.providerRefundId }
            .filter { it.amount.amount > 0 }
        if (usable.size != cancels.size) log.warn("payment provider cancels were skipped order={} skipped={}", orderId, cancels.size - usable.size)
        return usable
    }

    private fun checkRemaining(
        tx: PaymentLedgerTx, order: PaymentOrder, snapshot: PaymentSnapshot, context: ConvergeContext, staleSnapshot: Boolean, unappliedBefore: List<String>,
    ): PaymentOrder {
        if (RefundLines.remainingAgrees(order, snapshot)) {
            if (order.reviewReason != PaymentReviewReason.REFUND_MISMATCH) return order
            val lines = tx.listRefunds(order.id)
            // 결제사가 접수했다고 답한 줄이 열려 있으면 사람이 누른 맞추기도 표시를 풀지 않는다 — 풀면 주문이 「확인 필요」에서 사라지고 줄은 열린 채 다음 환불을 막는다(닫는 길은 sync 의 기한 뒤 NOT_APPLIED)
            if (lines.any { it.status == PaymentRefundStatus.PENDING && it.failureCode == RefundSettlement.PROVIDER_PENDING }) return order
            // 사람이 봐야 하는 이상(풀리지 않은 PENDING 줄 · 다른 금액의 취소 · 늦게 도착한 성공 응답)은 사람이 누른 맞추기만 푼다
            if (context.source != ConvergeSource.SYNC && lines.any { it.status == PaymentRefundStatus.PENDING || it.failureCode == RefundSettlement.RESULT_MISMATCH || it.failureCode == RefundSettlement.LATE_RESULT }) return order
            val released = writer.touch(tx, order, PaymentEventType.CONVERGED, context.actor, context.actorId, "REFUND_MISMATCH_CLEARED") { it.copy(reviewReason = null) }
            // 돈 이유가 칸을 차지한 동안 죽은 지급 · 회수는 표시를 남기지 못했다 — 칸이 비는 이 트랜잭션에서 다시 넣는다. 사람이 누른 맞추기는 끝에서 스스로 다시 넣는다(겹쳐 넣지 않는다)
            if (context.source != ConvergeSource.SYNC) {
                supply.requeueFulfil(released)
                supply.requeueApply(tx, unappliedBefore)
            }
            return released
        }
        if (staleSnapshot) return order
        return flags.flag(tx, order, PaymentReviewReason.REFUND_MISMATCH, context)
    }
}
