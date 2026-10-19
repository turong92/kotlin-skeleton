package dev.sumin.skeleton.payment

import org.slf4j.LoggerFactory

/**
 * `payment-refund-reconcile` — 환불을 `PENDING` 으로 올릴 때 `settle-delay` 뒤로 넣은 작업. 줄이 아직 `PENDING` 이면 결제사에 물어 [RefundSettlement] 로 정산한다.
 * 결제사가 대답하지 못하면 던져서 다시 하고, **마지막 시도에서도 못 하면** 주문에 `REFUND_MISMATCH` 를 표시한 뒤 던진다(큐가 죽은 작업으로 경보를 낸다 — 환불 줄은 열린 채라 다음 환불을 막는다).
 * 결제사 결과가 장부와 맞지 않아도 `REFUND_MISMATCH` 를 표시하고 바로 영구 실패한다(헛돌지 않게) — 단 조회한 뒤 다른 환불 줄이 바뀌었으면 스냅샷이 낡았을 수 있어 표시하지 않고 다시 한다.
 * 결제사가 접수했다고 답한 환불이 조회에 아직 안 보이면 닫지 않고 같은 방식(재시도 → 마지막 시도에 표시)이다 — 이 작업은 기한이 지나도 닫지 않는다(닫는 것은 사람이 누른 「결제사와 맞추기」).
 *
 * **바깥 트랜잭션 안에서 부르면 던진다**(결제사를 부르는 코드다).
 */
class RefundReconcileTaskHandler(
    private val store: PaymentLedgerStore,
    private val router: PaymentProviderRouter,
    private val convergence: PaymentConvergence,
    private val writer: PaymentLedgerWriter,
    private val properties: PaymentProperties,
) : PaymentTaskHandler {
    private val log = LoggerFactory.getLogger(javaClass)
    private val settlement = RefundSettlement(convergence, writer)

    override val type: String = PaymentTaskTypes.REFUND_RECONCILE

    override fun handle(run: PaymentTaskRun) {
        store.requireNoOpenTransaction("The refund reconcile task")
        val refund = store.findRefund(run.subjectId) ?: return
        if (refund.status != PaymentRefundStatus.PENDING) return
        val order = store.findOrder(refund.orderId) ?: return
        val gateway = try {
            router.gateway(order.provider, PaymentLookupGateway::class)
        } catch (e: PaymentProviderNotFoundException) {
            throw retryAndFlagAtEnd(run, order, order.providerMode, "No payment provider is available to settle refund ${refund.id}")
        } catch (e: PaymentCapabilityUnsupportedException) {
            flag(order.id, order.providerMode, PaymentReviewReason.REFUND_MISMATCH)
            throw PaymentTaskPermanentException("The payment provider of refund ${refund.id} cannot be looked up")
        }
        if (gateway.mode != order.providerMode) {
            flag(order.id, gateway.mode, PaymentReviewReason.MODE_MISMATCH)
            throw PaymentTaskPermanentException("Refund ${refund.id} belongs to an order created under another payment mode")
        }
        val at = writer.now()
        val snapshot = try {
            gateway.lookup(PaymentLookupQuery(providerPaymentId = order.providerPaymentId, orderId = if (order.providerPaymentId == null) order.id else null))
        } catch (e: Exception) {
            log.warn("payment lookup failed for refund settle refund={} attempt={}/{} exception={}", refund.id, run.attempt, run.maxAttempts, e.javaClass.simpleName)
            throw retryAndFlagAtEnd(run, order, gateway.mode, "Payment lookup is unavailable for refund ${refund.id}")
        }
        val context = ConvergeContext(gateway.mode, ConvergeSource.REFUND, snapshotAt = at)
        val result = store.inTransaction { tx ->
            val locked = tx.lockOrder(order.id) ?: return@inTransaction RefundSettlement.Result.DONE
            settlement.settle(tx, locked, refund.id, snapshot, at, context, properties.refund)
        }
        when (result) {
            RefundSettlement.Result.DONE -> Unit
            RefundSettlement.Result.WAIT -> throw PaymentTaskRetryException("Refund ${refund.id} is not due for settlement yet")
            RefundSettlement.Result.PROVIDER_PENDING -> throw retryAndFlagAtEnd(run, order, gateway.mode, "Refund ${refund.id} was accepted by the payment provider but does not show in the lookup yet")
            RefundSettlement.Result.MISMATCH -> throw PaymentTaskPermanentException("Refund ${refund.id} needs a person to review it")
            RefundSettlement.Result.HELD_BY_PROVIDER -> Unit // 결제사가 검토 중으로 쥐고 있다 - 결정은 웹훅이 끝낸다(헛도는 재시도 · 거짓 표시 없음)
            RefundSettlement.Result.STALE_SNAPSHOT -> throw PaymentTaskRetryException("Refund ${refund.id} changed while its lookup was running")
        }
    }

    /**
     * 정산 작업이 죽었다 — 환불 줄이 아직 `PENDING`(결제사에 보냈는데 결과를 모른다)이면 주문에 `REFUND_MISMATCH`(마지막 시도에 찍는 것과 같은 표시).
     * 열린 환불이 다음 환불을 막고 있으므로 사람이 봐야 한다. 줄은 그대로 둔다(결제사에서 환불됐는지는 사람이 확인한다). 이미 정산된 줄 · 이미 표시가 있는 주문은 건드리지 않는다 — 두 번 불려도 한 번.
     */
    override fun onDead(run: PaymentTaskRun) {
        val refund = store.findRefund(run.subjectId) ?: return
        if (refund.status != PaymentRefundStatus.PENDING) return
        store.inTransaction { tx ->
            val locked = tx.lockOrder(refund.orderId) ?: return@inTransaction
            // 락 안에서 다시 읽는다 — 그 사이 다른 길로 정산됐을 수 있다
            if (tx.lockRefund(refund.id)?.status != PaymentRefundStatus.PENDING) return@inTransaction
            convergence.flagReview(tx, locked, PaymentReviewReason.REFUND_MISMATCH, ConvergeContext(locked.providerMode, ConvergeSource.REFUND))
        }
    }

    private fun retryAndFlagAtEnd(run: PaymentTaskRun, order: PaymentOrder, mode: PaymentMode, message: String): PaymentTaskRetryException {
        if (run.lastAttempt) flag(order.id, mode, PaymentReviewReason.REFUND_MISMATCH)
        return PaymentTaskRetryException(message)
    }

    private fun flag(orderId: String, mode: PaymentMode, reason: PaymentReviewReason) {
        store.inTransaction { tx ->
            val locked = tx.lockOrder(orderId) ?: return@inTransaction
            convergence.flagReview(tx, locked, reason, ConvergeContext(mode, ConvergeSource.REFUND))
        }
    }
}

/** `payment-refund-apply` — 환불이 `SUCCEEDED` 가 된 뒤 앱의 회수([PaymentOrderListener.onRefunded])를 한 번. 리스너 예외의 문장은 싣지 않는다(우리 문장과 id 뿐). */
class RefundApplyTaskHandler(
    private val applier: PaymentRefundApplier,
    private val store: PaymentLedgerStore,
    private val convergence: PaymentConvergence,
) : PaymentTaskHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override val type: String = PaymentTaskTypes.REFUND_APPLY

    override fun handle(run: PaymentTaskRun) {
        try {
            applier.apply(run.subjectId)
        } catch (e: Exception) {
            log.warn("payment refund application failed refund={} attempt={}/{} exception={}", run.subjectId, run.attempt, run.maxAttempts, e.javaClass.simpleName)
            throw PaymentTaskRetryException("Refund application failed for refund ${run.subjectId}")
        }
    }

    /**
     * 회수 작업이 죽었다 — 환불 줄이 아직 `SUCCEEDED` 이고 `applied_at` 이 NULL 이면 주문에 `REFUND_APPLY_UNRESOLVED`(돈은 돌려줬는데 회수가 안 됐다). 락 안에서 줄을 다시 읽는다.
     * 돈 쪽 이유가 이미 있으면 덮지 않는다. 두 번 불려도 한 번. 닫는 길은 운영자의 「결제사와 맞추기」 — 회수 작업을 다시 넣고 이 표시를 푼다.
     */
    override fun onDead(run: PaymentTaskRun) {
        val orderId = store.findRefund(run.subjectId)?.orderId ?: return
        store.inTransaction { tx ->
            val locked = tx.lockOrder(orderId) ?: return@inTransaction
            val refund = tx.lockRefund(run.subjectId) ?: return@inTransaction
            if (refund.status != PaymentRefundStatus.SUCCEEDED || refund.appliedAt != null) return@inTransaction
            convergence.flagReview(tx, locked, PaymentReviewReason.REFUND_APPLY_UNRESOLVED, ConvergeContext(locked.providerMode, ConvergeSource.REFUND))
        }
    }
}
