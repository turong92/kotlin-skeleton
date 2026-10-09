package dev.sumin.skeleton.payment

/**
 * 모름으로 남은(`PENDING`) 환불을 결제사의 지금 상태로 정산한다(스펙 §6.4) — 조회 작업과 운영자의 「결제사와 맞추기」가 함께 쓴다. 주문 줄 락 안에서 부른다.
 * 먼저 [PaymentConvergence.converge] 로 결제사의 취소 목록을 장부에 맞추고(이 줄이 이뤄졌으면 거기서 `SUCCEEDED`), 그래도 `PENDING` 이면:
 * - 결제사의 남은 금액 = 장부의 남은 금액(성공한 환불만 뺀 값)이면 **적용되지 않았다** → `FAILED(NOT_APPLIED)`, 주문은 다시 환불할 수 있다. 운영자가 다시 누르면 새 줄 · 새 멱등 키다.
 *   단 보낸 지 `settle-delay` 가 지나지 않았으면 기다린다 — **지났는지는 스냅샷을 얻은 시각([snapshotAt] — 필수 인자)으로 잰다.** 조회를 먼저 하고 주문 락을 기다린 뒤 판정하면
 *   판정 시각은 기한 뒤여도 스냅샷은 기한 전일 수 있고, 그 스냅샷은 앞선 요청이 그 뒤에 적용됐는지 말해 주지 못한다.
 * - 결제사가 **접수했다고 답한 줄**([PROVIDER_PENDING], 응답이 "처리 중")은 자동으로 닫지 않는다 — 받았다고 말한 요청을 "안 나갔다"로 닫으면 늦은 적용이 정상 경로가 된다.
 *   결과는 [Result.PROVIDER_PENDING] 이고 정산 작업이 재시도하다 마지막 시도(와 죽은 작업)에 `REFUND_MISMATCH` 를 찍는다. **출구는 사람이 누른 「결제사와 맞추기」([ConvergeSource.SYNC])뿐이다:**
 *   보낸 지 `provider-pending-timeout` 이 지났고(역시 스냅샷 시각으로 잰다) 조회에 여전히 안 보이면 `NOT_APPLIED` 로 닫는다(이력의 이유 코드 [PROVIDER_PENDING_EXPIRED]).
 *   그 뒤 늦게 적용되면 다음 맞추기가 결제사 취소(`PROVIDER` 줄)로 잡는다(스펙 §15).
 * - 결제사가 **요청과 다른 금액**을 취소한 줄([RESULT_MISMATCH])은 사람이 누른 「결제사와 맞추기」([ConvergeSource.SYNC])만 닫는다 — 자동으로 닫으면 사람이 봐야 할 이상이 "콘솔 취소"로 남는다.
 * - 그 밖이면 `REFUND_MISMATCH` — 줄은 열린 채 남아 사람이 본다. **단 스냅샷이 낡았을 수 있으면**(스냅샷을 얻은 뒤에 바뀐 환불 줄이 있다) 숫자 대조를 건너뛰고 [Result.STALE_SNAPSHOT] — 다시 한다.
 * (결제사의 남은 금액 = `expected_remaining + amount` 와 같은 말이다. 사이에 결제사 관리 화면의 취소가 끼었어도 장부가 먼저 그것을 흡수해 비교가 어긋나지 않는다.)
 */
internal class RefundSettlement(
    private val convergence: PaymentConvergence,
    private val writer: PaymentLedgerWriter,
) {
    enum class Result { DONE, WAIT, MISMATCH, PROVIDER_PENDING, STALE_SNAPSHOT }

    /** [snapshotAt] 은 결제사에 묻기 **시작한** 시각 — 빠뜨릴 수 없다(기한 판정 · 낡은 스냅샷 판정이 모두 이 시각으로 한다). */
    fun settle(
        tx: PaymentLedgerTx,
        order: PaymentOrder,
        refundId: String,
        snapshot: PaymentSnapshot?,
        snapshotAt: java.time.Instant,
        baseContext: ConvergeContext,
        config: PaymentProperties.Refund,
    ): Result {
        val context = baseContext.copy(snapshotAt = snapshotAt)
        val refund = tx.lockRefund(refundId)
        if (refund == null || refund.status != PaymentRefundStatus.PENDING) return Result.DONE
        if (snapshot == null) {
            convergence.flagReview(tx, order, PaymentReviewReason.REFUND_MISMATCH, context)
            return Result.MISMATCH
        }
        // 낡았는지는 맞추기가 줄을 바꾸기 전에 잰다(맞추기가 흡수한 줄은 스냅샷 뒤의 일이 아니다)
        val stale = tx.listRefunds(order.id).any { !it.updatedAt.isBefore(snapshotAt) }
        val converged = convergence.converge(tx, order, snapshot, context).order
        val after = checkNotNull(tx.lockRefund(refundId))
        if (after.status != PaymentRefundStatus.PENDING) return Result.DONE
        if (after.failureCode == RESULT_MISMATCH && context.source != ConvergeSource.SYNC) {
            convergence.flagReview(tx, converged, PaymentReviewReason.REFUND_MISMATCH, context)
            return Result.MISMATCH
        }
        if (RefundLines.received(snapshot) && snapshot.remaining.amount == RefundLines.baseOf(converged) - converged.refundedAmount) {
            val sentAt = after.sentAt ?: after.requestedAt
            if (after.failureCode == PROVIDER_PENDING) {
                val expired = context.source == ConvergeSource.SYNC && !snapshotAt.isBefore(sentAt.plus(config.providerPendingTimeout))
                if (!expired) return Result.PROVIDER_PENDING
                close(tx, after, context, PROVIDER_PENDING_EXPIRED)
                return Result.DONE
            }
            if (snapshotAt.isBefore(sentAt.plus(config.settleDelay))) return Result.WAIT
            close(tx, after, context, NOT_APPLIED)
            return Result.DONE
        }
        if (stale) return Result.STALE_SNAPSHOT
        convergence.flagReview(tx, converged, PaymentReviewReason.REFUND_MISMATCH, context)
        return Result.MISMATCH
    }

    /** 줄을 `FAILED(NOT_APPLIED)` 로 닫는다. [historyCode] 는 이력의 이유 코드(줄의 `failure_code` 는 늘 `NOT_APPLIED`). */
    private fun close(tx: PaymentLedgerTx, refund: PaymentRefund, context: ConvergeContext, historyCode: String) {
        writer.transitionRefund(tx, refund, PaymentRefundStatus.FAILED, PaymentEventType.REFUND_FAILED, context.actor, context.actorId, historyCode) {
            it.copy(failureCode = NOT_APPLIED, completedAt = writer.now())
        }
    }

    companion object {
        const val NOT_APPLIED = "NOT_APPLIED"

        /** 이력의 `provider_code`: 결제사가 접수했다고 답한 줄을 기한이 지난 뒤 사람이 누른 맞추기가 `NOT_APPLIED` 로 닫았다. */
        const val PROVIDER_PENDING_EXPIRED = "PROVIDER_PENDING_EXPIRED"

        /** `PENDING` 줄의 `failure_code` · 이력의 `provider_code`: 결제사가 환불을 접수했다고(처리 중) 답했다. 줄이 닫히면 지워진다. */
        const val PROVIDER_PENDING = "PROVIDER_PENDING"

        /** `PENDING` 줄의 `failure_code` · 이력의 `provider_code`: 결제사가 요청과 다른 금액(또는 장부가 받을 수 없는 금액)을 취소했다고 답했다. 사람이 맞추기로 풀 때까지 남는다. */
        const val RESULT_MISMATCH = "RESULT_MISMATCH"

        /**
         * 이력의 `provider_code`, 그리고 **이미 닫힌 줄의 `failure_code`**: 환불됨 응답이 도착했는데 줄은 이미 닫혀 있고 응답이 말하는 취소를 장부가 몰랐다(또는 거래 키가 없었다).
         * 줄의 표시는 이 주문의 `REFUND_MISMATCH` 가 자동 맞추기(웹훅 · 조회)로 풀리지 않게 한다 — 사람이 누른 맞추기만 푼다. 닫힌 줄의 `NOT_APPLIED` 는 이력(`REFUND_FAILED`)에 남아 있다.
         */
        const val LATE_RESULT = "LATE_RESULT"
    }
}
