package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.FieldValidationException
import java.security.SecureRandom
import java.util.Random
import org.slf4j.LoggerFactory

/**
 * 환불(스펙 §6.4) — **본인은 요청만, 돈을 움직이는 것은 운영자.** 모든 전이는 주문 줄을 잠그고 환불 줄을 다시 읽은 뒤 판단한다(락 순서: 주문 → 환불, 한 트랜잭션 한 주문).
 * 결제사 호출은 락 · 트랜잭션 밖이다: **환불 전 확인(조회 → 맞추기) → 선점(`PENDING`, 커밋) → 호출 → 확정(커밋)**. 모름이면 **다시 보내지 않고** 조회 작업이 정산한다.
 * 환불 전 확인이 실패하면 환불을 보내지 않는다([PaymentRefundNotSentException]) — 다시 누른 환불은 새 줄 · 새 멱등 키다.
 *
 * 남의 주문 · 없는 주문은 [PaymentOrderNotFoundException], 남의 환불 · 없는 환불은 [PaymentRefundNotFoundException] 이다.
 * 결제사를 부르는 입구([refund] · [approve] · [sync])는 **바깥 트랜잭션 안에서 부르면 던진다**([IllegalStateException]).
 */
class PaymentRefunds(
    private val store: PaymentLedgerStore,
    tasks: PaymentTasks,
    private val router: PaymentProviderRouter,
    private val convergence: PaymentConvergence,
    private val applier: PaymentRefundApplier,
    private val writer: PaymentLedgerWriter,
    private val properties: PaymentProperties,
    random: Random = SecureRandom(),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lines = RefundLines(writer, tasks, random)
    private val settlement = RefundSettlement(convergence, writer)
    private val enqueue = tasks
    private val supply = SupplyWork(tasks)

    // --- 본인 ---

    /** 환불을 **요청**한다 — 돈은 움직이지 않는다. 같은 `Idempotency-Key` 로 다시 오면 앞선 줄을 그대로 돌려준다. [amount] 가 null 이면 남은 전액. */
    fun request(accountId: String, orderId: String, reasonCode: String, amount: Long?, key: IdempotencyKey): RequestedRefund {
        if (!properties.refund.requestsEnabled) throw PaymentRefundNotAllowedException()
        val reason = reasonOf(reasonCode, forOperator = false)
        requirePositive(amount)
        val dedupe = PaymentOrderIds.dedupeKey(accountId, key)
        store.findRefundByDedupeKey(dedupe)?.let { return RequestedRefund(replayed(it, orderId, reason, amount, PaymentRefundOrigin.USER), true) }
        return store.inTransaction { tx ->
            val order = tx.lockOrder(orderId)?.takeIf { it.accountId == accountId } ?: throw PaymentOrderNotFoundException()
            tx.findRefundByDedupeKey(dedupe)?.let { return@inTransaction RequestedRefund(replayed(it, orderId, reason, amount, PaymentRefundOrigin.USER), true) }
            val refunds = openable(order, tx.listRefunds(orderId))
            val value = amountFor(order, refunds, amount, excluding = null)
            val now = writer.now()
            val line = newLine(order, PaymentRefundStatus.REQUESTED, PaymentRefundOrigin.USER, value, reason, dedupe, accountId, now)
            val stored = writer.createRefund(tx, line, PaymentEventType.REFUND_REQUESTED, PaymentActor.USER, accountId)
            if (stored == null) {
                val winner = tx.findRefundByDedupeKey(dedupe) ?: throw PaymentRefundInProgressException()
                return@inTransaction RequestedRefund(replayed(winner, orderId, reason, amount, PaymentRefundOrigin.USER), true)
            }
            RequestedRefund(stored, false)
        }
    }

    /** 내 요청을 거둔다 — `REQUESTED` 일 때만. */
    fun withdraw(accountId: String, refundId: String): PaymentRefund {
        val orderId = store.findRefund(refundId)?.orderId ?: throw PaymentRefundNotFoundException()
        return store.inTransaction { tx ->
            tx.lockOrder(orderId)?.takeIf { it.accountId == accountId } ?: throw PaymentRefundNotFoundException()
            val refund = tx.lockRefund(refundId) ?: throw PaymentRefundNotFoundException()
            if (refund.status != PaymentRefundStatus.REQUESTED) throw PaymentRefundNotAllowedException()
            writer.transitionRefund(tx, refund, PaymentRefundStatus.WITHDRAWN, PaymentEventType.REFUND_WITHDRAWN, PaymentActor.USER, accountId) { it.copy(completedAt = writer.now()) }
        }
    }

    /** 내 주문의 환불 줄과 요약(`openRefundStatus` · `refundPending` · `refundableAmount`). */
    fun get(accountId: String, orderId: String): OrderRefunds {
        val order = store.findOrder(orderId)?.takeIf { it.accountId == accountId } ?: throw PaymentOrderNotFoundException()
        return view(order)
    }

    /** 운영자용: 계정과 상관없이 주문의 환불 줄과 요약. */
    fun view(orderId: String): OrderRefunds = view(store.findOrder(orderId) ?: throw PaymentOrderNotFoundException())

    fun view(order: PaymentOrder): OrderRefunds {
        val refunds = store.listRefunds(order.id)
        return OrderRefunds(order, refunds, PaymentRefundSummary.of(order, refunds))
    }

    // --- 운영자 ---

    /** 요청을 거절한다 — `REQUESTED` 일 때만(이미 결정된 줄은 [PaymentRefundNotAllowedException]). */
    fun reject(operatorId: String, refundId: String): PaymentRefund {
        val orderId = store.findRefund(refundId)?.orderId ?: throw PaymentRefundNotFoundException()
        return store.inTransaction { tx ->
            tx.lockOrder(orderId) ?: throw PaymentRefundNotFoundException()
            val refund = tx.lockRefund(refundId) ?: throw PaymentRefundNotFoundException()
            if (refund.status != PaymentRefundStatus.REQUESTED) throw PaymentRefundNotAllowedException()
            writer.transitionRefund(tx, refund, PaymentRefundStatus.REJECTED, PaymentEventType.REFUND_REJECTED, PaymentActor.OPERATOR, operatorId) {
                it.copy(decidedBy = operatorId, completedAt = writer.now())
            }
        }
    }

    /**
     * 요청 없이 바로 환불한다(origin `OPERATOR`). 같은 `Idempotency-Key` 로 다시 오면 **그 환불 줄을 지금 상태 그대로**(거절돼 `FAILED` 여도) 돌려주고 결제사를 부르지 않는다.
     * 결과의 줄이 `SUCCEEDED` 면 끝, `PENDING` 이면 모름(조회 작업이 정산). 확정 거절은 [PaymentRefundDeclinedException].
     */
    fun refund(operatorId: String, orderId: String, reasonCode: String, amount: Long?, key: IdempotencyKey): RefundOutcome {
        store.requireNoOpenTransaction("PaymentRefunds.refund")
        val reason = reasonOf(reasonCode, forOperator = true)
        requirePositive(amount)
        val dedupe = PaymentOrderIds.dedupeKey(operatorId, key)
        store.findRefundByDedupeKey(dedupe)?.let { return outcomeOf(replayed(it, orderId, reason, amount, PaymentRefundOrigin.OPERATOR), replayed = true) }
        val order0 = store.findOrder(orderId) ?: throw PaymentOrderNotFoundException()
        val gateways = precheck(order0, operatorId)
        val claimed = store.inTransaction { tx ->
            val order = tx.lockOrder(orderId) ?: throw PaymentOrderNotFoundException()
            tx.findRefundByDedupeKey(dedupe)?.let { return@inTransaction Claimed(order, replayed(it, orderId, reason, amount, PaymentRefundOrigin.OPERATOR), true) }
            val refunds = openable(order, tx.listRefunds(orderId))
            requireNoRefundMismatch(order)
            requireMode(gateways, order)
            val value = amountFor(order, refunds, amount, excluding = null)
            val now = writer.now()
            val line = newLine(order, PaymentRefundStatus.PENDING, PaymentRefundOrigin.OPERATOR, value, reason, dedupe, operatorId, now)
                .copy(decidedBy = operatorId, expectedRemaining = RefundLines.baseOf(order) - order.refundedAmount - value, sentAt = now)
            val stored = writer.createRefund(tx, line, PaymentEventType.REFUND_SENT, PaymentActor.OPERATOR, operatorId)
            if (stored == null) {
                // 유니크가 무시했다: 같은 요청(dedupe_key)을 먼저, 그다음 열린 자리(open_key) 순서로 가른다
                val winner = tx.findRefundByDedupeKey(dedupe)
                if (winner != null) return@inTransaction Claimed(order, replayed(winner, orderId, reason, amount, PaymentRefundOrigin.OPERATOR), true)
                throw PaymentRefundInProgressException()
            }
            enqueueSettle(stored)
            Claimed(order, stored, false)
        }
        if (claimed.replayed) return outcomeOf(claimed.refund, replayed = true)
        return execute(claimed.order, claimed.refund, gateways, operatorId)
    }

    /**
     * 본인의 요청을 승인해 실행한다. [amount] 가 없으면 요청 금액, 있으면 `0 < amount ≤ min(요청 금액, 환불 가능액)` — 넘으면 결제사를 부르지 않고 [PaymentRefundAmountExceededException].
     * 고친 금액이면 줄의 금액을 승인 금액으로 바꾸고 처음 요청한 금액은 [PaymentRefund.requestedAmount] 에 남긴다.
     * 이미 결정된 요청(동시 승인의 뒤쪽 포함)은 [PaymentRefundNotAllowedException].
     */
    fun approve(operatorId: String, refundId: String, amount: Long? = null): RefundOutcome {
        store.requireNoOpenTransaction("PaymentRefunds.approve")
        val refund0 = store.findRefund(refundId) ?: throw PaymentRefundNotFoundException()
        if (refund0.status != PaymentRefundStatus.REQUESTED) throw PaymentRefundNotAllowedException()
        val order0 = store.findOrder(refund0.orderId) ?: throw PaymentRefundNotFoundException()
        val gateways = precheck(order0, operatorId)
        val claimed = store.inTransaction { tx ->
            val order = tx.lockOrder(order0.id) ?: throw PaymentRefundNotFoundException()
            val refund = tx.lockRefund(refundId) ?: throw PaymentRefundNotFoundException()
            if (refund.status != PaymentRefundStatus.REQUESTED) throw PaymentRefundNotAllowedException() // 동시에 결정됐다
            if (!(order.status == PaymentOrderStatus.PAID || order.status == PaymentOrderStatus.PARTIALLY_REFUNDED)) throw PaymentRefundNotAllowedException()
            requireNoRefundMismatch(order)
            requireMode(gateways, order)
            val refunds = tx.listRefunds(order.id)
            val asked = refund.amount.amount
            val value = amountFor(order, refunds, amount ?: asked, excluding = refund.id)
            if (value > asked) throw PaymentRefundAmountExceededException() // 요청한 것보다 많이는 돌려주지 않는다
            val now = writer.now()
            val sent = writer.transitionRefund(tx, refund, PaymentRefundStatus.PENDING, PaymentEventType.REFUND_SENT, PaymentActor.OPERATOR, operatorId) {
                it.copy(amount = PaymentAmount(value, it.amount.currency), requestedAmount = asked.takeIf { _ -> value != asked }, decidedBy = operatorId, expectedRemaining = RefundLines.baseOf(order) - order.refundedAmount - value, sentAt = now)
            }
            enqueueSettle(sent)
            Claimed(order, sent, false)
        }
        return execute(claimed.order, claimed.refund, gateways, operatorId)
    }

    /**
     * 운영자가 **막힌 구독 결제 주문**(결제사가 끝내 답하지 않아 `CONFIRMING` 으로 남은 것)을 「결제 안 됨」(`FAILED`)으로 닫는다 — 구독이 그 주문 때문에 쥔 자리가 풀린다.
     * 닫기 전에 결제사에 **한 번 더 묻는다**: 돈이 들어간 것으로 나오면 닫지 않고 정상 `PAID` 처리로 맞추고, 아직 처리 중이거나 주문이 어긋나 있으면 [PaymentOrderNotClosableException].
     * 물을 수 없으면(결제사 오류 · 대답 없음) 닫지 않는다 — [sync] 와 같은 예외. 이력에 운영자와 `OPERATOR_CLOSED` 가 남는다.
     */
    fun closeStuckSubscriptionCharge(operatorId: String, orderId: String): PaymentOrder {
        store.requireNoOpenTransaction("PaymentRefunds.closeStuckSubscriptionCharge")
        val order0 = store.findOrder(orderId) ?: throw PaymentOrderNotFoundException()
        if (order0.originType != PaymentOriginType.SUBSCRIPTION || order0.status != PaymentOrderStatus.CONFIRMING) throw PaymentOrderNotClosableException()
        val gateway = try {
            router.gateway(order0.provider, PaymentLookupGateway::class)
        } catch (e: PaymentProviderNotFoundException) {
            log.warn("closing a subscription charge: the provider is not available order={} provider={}", orderId, e.providerId)
            throw PaymentUnavailableException()
        }
        if (gateway.mode != order0.providerMode) throw PaymentModeMismatchException()
        val at = writer.now()
        val snapshot = try {
            gateway.lookup(PaymentLookupQuery(providerPaymentId = order0.providerPaymentId, orderId = if (order0.providerPaymentId == null) order0.id else null))
        } catch (e: PaymentProviderException) {
            throw e
        } catch (e: Exception) {
            log.warn("closing a subscription charge: the lookup failed order={} exception={}", orderId, e.javaClass.simpleName)
            throw PaymentUnavailableException()
        }
        return store.inTransaction { tx ->
            val ctx = ConvergeContext(gateway.mode, ConvergeSource.SYNC, PaymentActor.OPERATOR, operatorId, at)
            val locked = tx.lockOrder(orderId) ?: throw PaymentOrderNotFoundException()
            if (locked.status != PaymentOrderStatus.CONFIRMING) return@inTransaction locked // 그 사이 다른 길이 끝냈다 — 닫을 것이 없다
            if (snapshot == null) return@inTransaction convergence.declined(tx, locked, OPERATOR_CLOSED, ctx).order
            // 결제사가 아는 결제다 — 받았다면 정상 PAID, 실패 · 만료면 정상으로 닫힌다. 그 어느 쪽도 아니면(처리 중 · 어긋남) 닫지 않는다
            val converged = convergence.converge(tx, locked, snapshot, ctx).order
            if (converged.status == PaymentOrderStatus.CONFIRMING) throw PaymentOrderNotClosableException()
            converged
        }
    }

    /**
     * 「결제사와 맞추기」 — 결제사에 묻고 장부를 맞춘다(결제사 관리 화면의 취소 포함). 모름으로 남은 환불이 있으면 정산한다. 돌려주는 것은 맞춘 뒤의 주문.
     * 물을 수 없으면 [PaymentProviderException](결제사가 오류로 답함) 또는 [PaymentUnavailableException](대답이 없음).
     * 모드가 다르면 409([PaymentModeMismatchException]) — 돈이 움직였을 수 있는 `CONFIRMING` · 결제된 주문에는 `MODE_MISMATCH` 를 표시하고, `PENDING`(승인을 선점한 적 없다 — 만료 작업이 닫는다)과 끝난 주문([isFinished])에는 표시하지 않는다.
     */
    fun sync(operatorId: String, orderId: String): PaymentOrder {
        store.requireNoOpenTransaction("PaymentRefunds.sync")
        val order0 = store.findOrder(orderId) ?: throw PaymentOrderNotFoundException()
        val gateway = try {
            router.gateway(order0.provider, PaymentLookupGateway::class)
        } catch (e: PaymentProviderNotFoundException) {
            log.warn("payment sync: the provider is not available order={} provider={}", orderId, e.providerId)
            throw PaymentUnavailableException()
        }
        val context = { at: java.time.Instant? -> ConvergeContext(gateway.mode, ConvergeSource.SYNC, PaymentActor.OPERATOR, operatorId, at) }
        if (gateway.mode != order0.providerMode) {
            // 끝난 주문(닫힌 상태 · 열린 환불 없는 전액 환불)과 `PENDING` 은 돈이 걸려 있지 않아 표시하지 않고 409 만 낸다(웹훅 처리기가 `CONFIRMING` 에만 표시하는 것과 같은 결)
            store.inTransaction { tx ->
                val locked = tx.lockOrder(orderId) ?: return@inTransaction
                if (locked.status != PaymentOrderStatus.PENDING && !isFinished(locked, tx.listRefunds(orderId))) convergence.flagReview(tx, locked, PaymentReviewReason.MODE_MISMATCH, context(null))
            }
            throw PaymentModeMismatchException()
        }
        val at = writer.now()
        val snapshot = try {
            gateway.lookup(PaymentLookupQuery(providerPaymentId = order0.providerPaymentId, orderId = if (order0.providerPaymentId == null) order0.id else null))
        } catch (e: PaymentProviderException) {
            throw e
        } catch (e: Exception) {
            log.warn("payment sync lookup failed order={} exception={}", orderId, e.javaClass.simpleName)
            throw PaymentUnavailableException()
        }
        return store.inTransaction { tx ->
            val ctx = context(at)
            val found = tx.lockOrder(orderId) ?: throw PaymentOrderNotFoundException()
            // 결제사가 그 결제를 모르거나 아직 끝 상태를 말하지 못하면 모드 표시를 풀지 않는다 — 풀면 확정 중 · 결제 전 주문의 죽은 조회 · 만료 작업이 돌아오지 않은 채 목록에서 사라진다
            if (snapshot == null) throw PaymentProviderPaymentNotFoundException() // 결제사에 그 결제가 없다 — 맞출 것이 없고 장부는 그대로다(모드가 달랐다면 위에서 표시를 찍고 409 로 이미 끝났다 — 이 줄에 닿은 호출은 표시를 찍지 않았다)
            val locked = if (snapshot.status == ProviderPaymentStatus.IN_PROGRESS) found else releaseModeMismatch(tx, found, ctx)
            // 맞추기 전에 이미 있던 일감만 다시 넣는다 — 이 맞추기가 새로 만든 것은 그 자리에서 작업이 들어갔다
            val wasPaid = locked.status.isPaidFamily
            val unapplied = SupplyWork.unapplied(tx.listRefunds(orderId))
            val converged = convergence.converge(tx, locked, snapshot, ctx).order
            val pending = tx.listRefunds(orderId).filter { it.status == PaymentRefundStatus.PENDING }
            pending.forEach { settlement.settle(tx, tx.lockOrder(orderId)!!, it.id, snapshot, at, ctx, properties.refund) }
            // 정산이 줄을 닫았으면 그 줄이 쥐고 있던 표시를 풀 수 있는지 한 번 더 본다(같은 스냅샷 — 몇 번 불려도 결과가 같다)
            if (pending.isNotEmpty()) convergence.converge(tx, tx.lockOrder(orderId)!!, snapshot, ctx)
            requeueSupplyWork(tx, orderId, wasPaid, unapplied, ctx)
            tx.lockOrder(orderId) ?: converged
        }
    }

    /** 끝난 주문 — 닫힌 상태(`FAILED` · `EXPIRED` · `CANCELED`)이거나 전액 환불(`REFUNDED`)이고 열린 환불 줄이 없다. 열린 환불 줄이 있으면 돈이 걸려 있어 끝난 주문이 아니다. */
    private fun isFinished(order: PaymentOrder, refunds: List<PaymentRefund>): Boolean =
        (order.status.isClosed || order.status == PaymentOrderStatus.REFUNDED) && refunds.none { PaymentRefundTransitions.isOpen(it.status) }

    /**
     * `MODE_MISMATCH` 는 지금 끼워진 결제사의 모드가 주문의 모드와 같으면 풀린다(이 맞추기에 들어왔다는 것이 곧 같다는 뜻이다). 다른 이유는 건드리지 않는다.
     * 결제사가 그 결제를 모르거나 끝 상태를 말하지 못하면(조회가 null · `IN_PROGRESS`) 부르지 않는다. 풀린 뒤의 미지급 · 미회수는 [sync] 끝의 [requeueSupplyWork] 가 다시 넣는다.
     */
    private fun releaseModeMismatch(tx: PaymentLedgerTx, order: PaymentOrder, ctx: ConvergeContext): PaymentOrder {
        if (order.reviewReason != PaymentReviewReason.MODE_MISMATCH || order.providerMode != ctx.currentMode) return order
        return writer.touch(tx, order, PaymentEventType.CONVERGED, ctx.actor, ctx.actorId, "MODE_MISMATCH_CLEARED") { it.copy(reviewReason = null) }
    }

    /**
     * 사람이 누른 맞추기가 **끝나지 않은 지급 · 회수**를 다시 돌린다: 맞추기 전부터 미지급이던 `PAID` 계열 주문(지급 전에 전액 환불된 주문은 지급을 건너뛰므로 제외)에 지급 작업을,
     * 맞추기 전부터 미회수이던 `SUCCEEDED` 환불에 회수 작업을 **한 번씩** 넣는다(작업은 멱등이지만 한 번의 맞추기에 한 번만). 그 뒤 두 지급 쪽 이유(`FULFILMENT_UNRESOLVED` ·
     * `REFUND_APPLY_UNRESOLVED`)는 푼다 — 다시 죽으면 죽은 작업의 고리가 다시 찍는다. 돈 쪽 이유는 건드리지 않는다.
     */
    private fun requeueSupplyWork(tx: PaymentLedgerTx, orderId: String, wasPaid: Boolean, unapplied: List<String>, ctx: ConvergeContext) {
        val current = tx.lockOrder(orderId) ?: return
        if (wasPaid) supply.requeueFulfil(current)
        supply.requeueApply(tx, unapplied)
        val reason = current.reviewReason ?: return
        if (!reason.isMoney) {
            writer.touch(tx, current, PaymentEventType.CONVERGED, ctx.actor, ctx.actorId, reason.name + "_CLEARED") { it.copy(reviewReason = null) }
        }
    }

    // --- 안쪽 ---

    private class Gateways(val refund: PaymentRefundGateway, val lookup: PaymentLookupGateway)

    private class Claimed(val order: PaymentOrder, val refund: PaymentRefund, val replayed: Boolean)

    /**
     * ⓪ 환불 전 확인: 결제사에 묻고 [PaymentConvergence.converge] 로 장부를 먼저 맞춘다(장부가 모르는 취소 · 늦게 도착한 앞선 환불). **물을 수 없으면 환불을 보내지 않는다**
     * ([PaymentRefundNotSentException]) — 오류 뒤 멱등 키를 바꿔 같은 요청을 다시 보내는 것은 위험하다.
     * **`REFUND_MISMATCH` 표시가 이미 있거나, 결제사에 그 결제가 없거나 「받지 않았다」(진행 중 · 실패 · 만료)고 하거나, 맞춘 뒤에도 결제사의 남은 금액이 장부와 다르면 보내지 않는다**([PaymentRefundNotAllowedException]) — 숫자가 어긋난 채 돈을 더 보내지 않는다. 먼저 「결제사와 맞추기」.
     * 판정은 검토 이유 칸에 기대지 않고 **맞춘 뒤의 숫자**로 한다(다른 이유가 칸을 차지한 주문에서도 같다). 이미 있는 표시는 묻기 전에 막고(이 확인의 맞추기가 표시를 풀고 곧바로 보내는 길이 되지 않게),
     * 선점 락 안에서 한 번 더 본다([requireNoRefundMismatch]).
     */
    private fun precheck(order: PaymentOrder, actorId: String): Gateways {
        if (!(order.status == PaymentOrderStatus.PAID || order.status == PaymentOrderStatus.PARTIALLY_REFUNDED) || order.providerPaymentId == null) throw PaymentRefundNotAllowedException()
        if (order.reviewReason == PaymentReviewReason.REFUND_MISMATCH) throw PaymentRefundNotAllowedException()
        val gateways = notSent { Gateways(router.gateway(order.provider, PaymentRefundGateway::class), router.gateway(order.provider, PaymentLookupGateway::class)) }
        requireMode(gateways, order)
        val at = writer.now()
        val snapshot = try {
            gateways.lookup.lookup(PaymentLookupQuery(order.providerPaymentId, null))
        } catch (e: Exception) {
            log.warn("refund pre-check lookup failed order={} exception={}; no refund was sent", order.id, e.javaClass.simpleName)
            throw PaymentRefundNotSentException()
        }
        val context = ConvergeContext(gateways.lookup.mode, ConvergeSource.REFUND, PaymentActor.OPERATOR, actorId, at)
        val blocked = store.inTransaction { tx ->
            val locked = tx.lockOrder(order.id) ?: throw PaymentOrderNotFoundException()
            if (snapshot == null || !RefundLines.received(snapshot)) {
                // 장부는 결제됐다는데 결제사에 그 결제가 없거나 「받지 않았다」(진행 중 · 실패 · 만료)고 한다 — 환불을 보내지 않는다
                convergence.flagReview(tx, locked, PaymentReviewReason.REFUND_MISMATCH, context)
                return@inTransaction true
            }
            val converged = convergence.converge(tx, locked, snapshot, context).order
            // 판정은 검토 이유 칸이 아니라 맞춘 뒤의 숫자로 한다 — 칸을 다른 이유가 차지하고 있으면 표시가 찍히지 않는다
            converged.reviewReason == PaymentReviewReason.REFUND_MISMATCH || !RefundLines.remainingAgrees(converged, snapshot)
        }
        if (blocked) throw PaymentRefundNotAllowedException()
        return gateways
    }

    /**
     * 환불 전 확인 구간 **안에서만** 결제사를 고르지 못한 실패(없음 · 길 못 찾음 · 능력 없음)를 `503 UNAVAILABLE`("아무것도 보내지 않았다")로 옮긴다 — 장부 코드(`PROVIDER_NOT_FOUND` 등)가
     * 프론트에서 "모름"으로 읽히는 일이 없게. 이 함수는 [precheck] 에서만 불리므로 **환불 줄을 선점한 뒤에는 이 번역이 걸릴 수 없다**(위치가 보증한다).
     */
    private inline fun <T> notSent(call: () -> T): T =
        try {
            call()
        } catch (e: PaymentProviderNotFoundException) {
            log.warn("refund not sent: the payment provider is not available provider={}", e.providerId)
            throw PaymentUnavailableException()
        } catch (e: PaymentRoutingException) {
            log.warn("refund not sent: no payment provider route")
            throw PaymentUnavailableException()
        } catch (e: PaymentCapabilityUnsupportedException) {
            log.warn("refund not sent: {}", e.message)
            throw PaymentUnavailableException()
        }

    /** 선점 락 안: 확인과 선점 사이에 `REFUND_MISMATCH` 가 찍혔으면 보내지 않는다. */
    private fun requireNoRefundMismatch(order: PaymentOrder) {
        if (order.reviewReason == PaymentReviewReason.REFUND_MISMATCH) throw PaymentRefundNotAllowedException()
    }

    private fun requireMode(gateways: Gateways, order: PaymentOrder) {
        if (gateways.refund.mode != order.providerMode || gateways.lookup.mode != order.providerMode) throw PaymentModeMismatchException()
    }

    /** 락 안: 환불할 수 있는 상태이고 열린 환불이 없어야 한다. 돌려주는 것은 이 주문의 환불 줄. */
    private fun openable(order: PaymentOrder, refunds: List<PaymentRefund>): List<PaymentRefund> {
        if (!(order.status == PaymentOrderStatus.PAID || order.status == PaymentOrderStatus.PARTIALLY_REFUNDED)) throw PaymentRefundNotAllowedException()
        if (refunds.any { PaymentRefundTransitions.isOpen(it.status) }) throw PaymentRefundInProgressException()
        return refunds
    }

    /** 락 안: `결제액 - 환불된 금액 - 열린 환불`([excluding] 줄은 뺀다) 과 견준다. 생략하면 남은 전액. */
    private fun amountFor(order: PaymentOrder, refunds: List<PaymentRefund>, requested: Long?, excluding: String?): Long {
        val open = refunds.filter { PaymentRefundTransitions.isOpen(it.status) && it.id != excluding }.sumOf { it.amount.amount }
        val refundable = RefundLines.baseOf(order) - order.refundedAmount - open
        val value = requested ?: refundable
        if (value <= 0 || value > refundable) throw PaymentRefundAmountExceededException()
        return value
    }

    private fun newLine(order: PaymentOrder, status: PaymentRefundStatus, origin: PaymentRefundOrigin, amount: Long, reason: PaymentRefundReason, dedupe: String, by: String, now: java.time.Instant) =
        PaymentRefund(
            id = lines.newRefundId(), orderId = order.id, status = status, origin = origin, amount = PaymentAmount(amount, order.amount.currency), reasonCode = reason.name,
            openKey = null, dedupeKey = dedupe, providerRefundId = null, expectedRemaining = null, requestedBy = by, decidedBy = null, failureCode = null,
            requestedAt = now, sentAt = null, completedAt = null, appliedAt = null, createdAt = now, updatedAt = now,
        )

    private fun enqueueSettle(refund: PaymentRefund) {
        enqueue.enqueue(PaymentTask(PaymentTaskTypes.REFUND_RECONCILE, refund.id, runAt = writer.now().plus(properties.refund.settleDelay), maxAttempts = properties.reconcile.maxAttempts))
    }

    /** ② 호출(락 밖)과 ③ 확정. */
    private fun execute(order: PaymentOrder, refund: PaymentRefund, gateways: Gateways, operatorId: String): RefundOutcome {
        val request = PaymentRefundRequest(
            providerPaymentId = checkNotNull(order.providerPaymentId), amount = refund.amount, reason = PaymentRefundReason.parse(refund.reasonCode)?.providerText,
            provider = order.provider, idempotencyKey = IdempotencyKey(refund.id),
        )
        val result = try {
            gateways.refund.refund(request)
        } catch (e: Exception) {
            return when (PaymentFailures.outcomeOf(e)) {
                PaymentFailureOutcome.DEFINITIVE -> decline(order, refund, gateways, PaymentFailures.providerCodeOf(e), operatorId)
                PaymentFailureOutcome.UNKNOWN -> unknown(order, refund, operatorId, e.javaClass.simpleName)
            }
        }
        return settle(order, refund, gateways, result, operatorId)
    }

    private fun decline(order: PaymentOrder, refund: PaymentRefund, gateways: Gateways, providerCode: String?, operatorId: String): RefundOutcome {
        val code = LedgerValues.code(providerCode) ?: "REFUND_DECLINED"
        val closed = store.inTransaction { tx ->
            tx.lockOrder(order.id) ?: return@inTransaction null
            val current = tx.lockRefund(refund.id) ?: return@inTransaction null
            if (current.status != PaymentRefundStatus.PENDING) return@inTransaction current // 그 사이 다른 길이 먼저 끝냈다
            writer.transitionRefund(tx, current, PaymentRefundStatus.FAILED, PaymentEventType.REFUND_FAILED, PaymentActor.OPERATOR, operatorId, code) {
                it.copy(failureCode = code, completedAt = writer.now())
            }
        }
        if (closed?.status == PaymentRefundStatus.FAILED) throw PaymentRefundDeclinedException(gateways.refund.refundDeclineCategory(code), code)
        return outcomeOf(closed ?: refund)
    }

    private fun unknown(order: PaymentOrder, refund: PaymentRefund, operatorId: String, cause: String): RefundOutcome {
        log.warn("payment refund outcome unknown refund={} order={} exception={}", refund.id, order.id, cause)
        store.inTransaction { tx ->
            tx.lockOrder(order.id) ?: return@inTransaction
            val current = tx.lockRefund(refund.id) ?: return@inTransaction
            if (current.status == PaymentRefundStatus.PENDING) {
                tx.appendEvent(writer.refundEvent(order.id, refund.id, PaymentEventType.REFUND_UNKNOWN, PaymentActor.OPERATOR, operatorId, from = current.status, to = current.status))
            }
        }
        return outcomeOf(refund)
    }

    /**
     * 응답이 있을 때: 환불됨이고 금액 · 남은 금액 · 거래 키가 줄과 맞고 장부가 받을 수 있는 금액일 때만 `SUCCEEDED`. 맞지 않으면 줄을 `PENDING` 으로 두고 조회 정산에 맡긴다
     * (어긋남이면 `REFUND_MISMATCH` 와 줄의 `failure_code=RESULT_MISMATCH` — 사람이 맞추기로 풀 때까지 자동으로 닫지 않는다).
     * 응답이 "처리 중"이면 줄에 접수 표시(`PROVIDER_PENDING`)를 남긴다 — 타임아웃(아무 답이 없었다)과 달리 정산이 `NOT_APPLIED` 로 닫지 않는다.
     * **줄이 이미 `PENDING` 이 아닌데** 응답이 장부가 모르는 취소를 말하면 그 증거를 버리지 않는다([lateResult]).
     */
    private fun settle(order: PaymentOrder, refund: PaymentRefund, gateways: Gateways, result: PaymentOperationResult, operatorId: String): RefundOutcome {
        val amountOk = result.amount?.sameAs(refund.amount) == true
        val remainingOk = result.remaining == null || (result.remaining.amount == refund.expectedRemaining && result.remaining.normalizedCurrency == refund.amount.normalizedCurrency)
        val transaction = LedgerValues.code(result.providerOperationId)
        val refunded = result.status == PaymentOperationStatus.REFUNDED
        val samePayment = result.providerPaymentId == order.providerPaymentId
        val clean = refunded && amountOk && remainingOk && transaction != null && samePayment
        val context = ConvergeContext(gateways.refund.mode, ConvergeSource.REFUND, PaymentActor.OPERATOR, operatorId)
        // 줄이 이미 닫혔을 것 같으면 락 밖에서 먼저 결제사의 취소 목록을 받아 둔다(락 안에서는 결제사를 부르지 않는다). 락 안에서 다시 확인한다.
        val lateHint = refunded && samePayment && store.findRefund(refund.id)?.let { isLate(it, transaction) } == true
        val at = writer.now()
        val lateSnapshot = if (lateHint) lookupQuietly(order, gateways) else null
        store.inTransaction { tx ->
            val locked = tx.lockOrder(order.id) ?: return@inTransaction
            val current = tx.lockRefund(refund.id) ?: return@inTransaction
            if (current.status != PaymentRefundStatus.PENDING) {
                if (refunded && samePayment) lateResult(tx, locked, current, transaction, lateSnapshot, context.copy(snapshotAt = at), operatorId)
                return@inTransaction
            }
            val fits = lines.fits(locked, current.amount)
            when {
                clean && fits -> lines.succeed(tx, locked, current, transaction, writer.now(), PaymentActor.OPERATOR, operatorId)
                refunded && result.amount != null && (!(amountOk && remainingOk) || (clean && !fits)) -> {
                    convergence.flagReview(tx, locked, PaymentReviewReason.REFUND_MISMATCH, context)
                    writer.transitionRefund(tx, current, PaymentRefundStatus.PENDING, PaymentEventType.REFUND_UNKNOWN, PaymentActor.OPERATOR, operatorId, RefundSettlement.RESULT_MISMATCH) {
                        it.copy(failureCode = RefundSettlement.RESULT_MISMATCH)
                    }
                }
                result.status == PaymentOperationStatus.PENDING && samePayment && current.failureCode == null ->
                    writer.transitionRefund(tx, current, PaymentRefundStatus.PENDING, PaymentEventType.REFUND_UNKNOWN, PaymentActor.OPERATOR, operatorId, RefundSettlement.PROVIDER_PENDING) {
                        it.copy(failureCode = RefundSettlement.PROVIDER_PENDING)
                    }
                else -> tx.appendEvent(writer.refundEvent(order.id, refund.id, PaymentEventType.REFUND_UNKNOWN, PaymentActor.OPERATOR, operatorId, null, current.status, current.status))
            }
        }
        val after = outcomeOf(refund)
        if (after.refund.status == PaymentRefundStatus.SUCCEEDED) applier.tryApply(refund.id)
        return after
    }

    /** 환불됨 응답이 닫힌 줄에 도착했고 그 응답이 말하는 취소를 장부가 모를 수 있는가(락 밖의 추측 — 락 안에서 다시 확인한다). 거래 키가 없는 응답은 `FAILED` 줄에서만 의미가 있다. */
    private fun isLate(line: PaymentRefund, transaction: String?): Boolean =
        line.status != PaymentRefundStatus.PENDING && if (transaction != null) line.providerRefundId != transaction else line.status == PaymentRefundStatus.FAILED

    /**
     * 환불됨 응답이 도착했는데 줄은 이미 `PENDING` 이 아니다(다른 길이 먼저 `NOT_APPLIED` 로 닫았다 · 같은 금액의 콘솔 취소가 이 줄에 짝지어졌다).
     * 응답의 거래 키를 장부의 어느 줄도 모르면 **나간 돈이 장부에 없다** — 이력(`LATE_RESULT`)과 로그를 남기고, 받아 둔 결제사의 취소 목록으로 [PaymentConvergence.converge] 를 돌려 장부에 넣은 뒤
     * 줄에 [RefundSettlement.LATE_RESULT] 를 달고 마지막에 `REFUND_MISMATCH` 를 찍는다(다시 눌러 이중 환불이 되지 않게 — 표시가 있는 주문에는 환불을 보내지 않는다. 줄의 표시는 이 표시가
     * 자동 맞추기로 몇 초 만에 풀리지 않게 한다 — 사람이 누른 맞추기만 푼다). 목록을 못 받았으면 표시만 한다.
     * **거래 키가 없는 응답**이 `FAILED` 줄에 도착해도 같다 — 어느 취소인지 알 수 없으니 조용히 버리지 않고 표시한다(`SUCCEEDED` 줄은 이미 그 취소를 안다).
     */
    private fun lateResult(tx: PaymentLedgerTx, order: PaymentOrder, current: PaymentRefund, transaction: String?, snapshot: PaymentSnapshot?, context: ConvergeContext, operatorId: String) {
        if (transaction == null) {
            if (current.status != PaymentRefundStatus.FAILED) return
        } else if (tx.listRefunds(order.id).any { it.providerRefundId == transaction }) {
            return
        }
        log.warn("payment refund result arrived after the line was closed refund={} order={} status={} withTransactionKey={}", current.id, order.id, current.status, transaction != null)
        val converged = if (snapshot != null) convergence.converge(tx, order, snapshot, context).order else order
        tx.appendEvent(writer.refundEvent(order.id, current.id, PaymentEventType.REFUND_UNKNOWN, PaymentActor.OPERATOR, operatorId, RefundSettlement.LATE_RESULT, current.status, current.status))
        tx.lockRefund(current.id)?.takeIf { it.failureCode == null || it.failureCode == RefundSettlement.NOT_APPLIED }?.let {
            tx.updateRefund(it.copy(failureCode = RefundSettlement.LATE_RESULT, updatedAt = writer.now()))
        }
        convergence.flagReview(tx, converged, PaymentReviewReason.REFUND_MISMATCH, context)
    }

    private fun lookupQuietly(order: PaymentOrder, gateways: Gateways): PaymentSnapshot? =
        try {
            gateways.lookup.lookup(PaymentLookupQuery(order.providerPaymentId, null))
        } catch (e: Exception) {
            log.warn("payment refund late-result lookup failed order={} exception={}", order.id, e.javaClass.simpleName)
            null
        }

    private fun outcomeOf(refund: PaymentRefund, replayed: Boolean = false): RefundOutcome {
        val current = store.findRefund(refund.id) ?: refund
        return RefundOutcome(current, checkNotNull(store.findOrder(current.orderId)), replayed)
    }

    private fun replayed(existing: PaymentRefund, orderId: String, reason: PaymentRefundReason, amount: Long?, origin: PaymentRefundOrigin): PaymentRefund {
        if (existing.orderId != orderId || existing.origin != origin || existing.reasonCode != reason.name || (amount != null && existing.amount.amount != amount)) {
            throw PaymentIdempotencyConflictException()
        }
        return existing
    }

    private fun reasonOf(code: String, forOperator: Boolean): PaymentRefundReason {
        val reason = PaymentRefundReason.parse(code)
        if (reason == null || !(if (forOperator) reason.forOperator else reason.forCustomer)) {
            throw FieldValidationException("reasonCode", "Pattern", "reasonCode is not one of the allowed refund reasons")
        }
        return reason
    }

    private companion object {
        const val OPERATOR_CLOSED = "OPERATOR_CLOSED"
    }

    private fun requirePositive(amount: Long?) {
        if (amount != null && amount <= 0) throw FieldValidationException("amount", "Positive", "amount must be positive")
    }
}
