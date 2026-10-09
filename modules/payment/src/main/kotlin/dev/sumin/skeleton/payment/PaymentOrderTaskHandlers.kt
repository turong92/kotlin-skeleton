package dev.sumin.skeleton.payment

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** 사람이 봐야 해서 다시 해 봐야 소용없는 검토 이유 — 큐가 재시도 · 경보를 쌓지 않고 바로 죽인다. */
private val PERMANENT_REVIEWS = setOf(PaymentReviewReason.MODE_MISMATCH, PaymentReviewReason.AMOUNT_MISMATCH, PaymentReviewReason.ORDER_MISMATCH)

/**
 * 줄 id 의 주문을 잠그고 아직 [expected] 상태이면 [reason] 을 표시한다(경보는 처음 한 번). 반환은 표시한 뒤의 줄 —
 * 그 사이 다른 길로 끝났거나(상태가 달라짐) 줄이 없으면 표시하지 않고 그 줄 · null 을 돌려준다.
 */
private fun flagIfStillOpen(
    store: PaymentLedgerStore,
    convergence: PaymentConvergence,
    id: String,
    expected: PaymentOrderStatus,
    reason: PaymentReviewReason,
    context: ConvergeContext,
): PaymentOrder? =
    store.inTransaction { tx ->
        val locked = tx.lockOrder(id) ?: return@inTransaction null
        if (locked.status != expected) locked else convergence.flagReview(tx, locked, reason, context).order
    }

/** 결제사 빈이 없다 — 설정으로 고쳐지는 상태다. 능력이 없는 결제사만 영구([PaymentCapabilityUnsupportedException]). */
private fun lookupGatewayOrNull(router: PaymentProviderRouter, order: PaymentOrder, log: Logger): PaymentLookupGateway? =
    try {
        router.gateway(order.provider, PaymentLookupGateway::class)
    } catch (e: PaymentProviderNotFoundException) {
        log.warn("no payment provider bean to look up order={} provider={}", order.id, order.provider)
        null
    }

/**
 * `payment-order-expire` — 주문 만들 때 `checkout_expires_at` 에 넣은 작업. 줄이 아직 `PENDING` 이면 결제사에 **주문 번호로** 한 번 묻는다:
 * 결제가 없거나 끝난 실패면 `EXPIRED`, 돈이 들어왔으면 `PAID`, 아직 진행 중이면 던져서 백오프 뒤 다시. 줄이 그 사이 다른 길로 끝났으면 아무것도 하지 않는다
 * (조회는 락 밖이고 판단은 락 안에서 줄을 다시 읽고 한다).
 *
 * **모드를 먼저 본다**: 줄의 모드와 지금 결제사의 모드가 다르면 조회하지 않는다 — 다른 모드의 키로 물으면 "결제 없음"이나 인증 오류가 와서 헛돌기 때문이다.
 * `PENDING` 은 승인을 선점한 적이 없어 돈이 움직였을 수 없으므로(**전제**: 결제창 결제사는 서버 승인 없이 돈을 움직이지 않는다 — [PaymentCheckoutGateway]) **표시 · 경보 없이**
 * 마감이 지났으면 `EXPIRED` 로 닫고, 마감 전이면 큐가 나중에 다시 부른다(모드가 다른 `CONFIRMING` 은 돈이 움직였을 수 있어 [OrderReconcileTaskHandler] 가 `MODE_MISMATCH` 를 표시한다).
 * **결제사 빈이 없거나 꺼진 경우도 같다**(같은 근거 — 선점한 적 없다): 마감이 지났으면 조회 없이 닫고 마감 전이면 재시도. 예외는 결제사가 "받았다"고 답한 적이 있는 줄(`AMOUNT_MISMATCH` ·
 * `ORDER_MISMATCH` 표시) — 조회 없는 길에서는 닫지 않고 영구 실패로 던진다(같은 모드 경로의 끝과 같다).
 *
 * **바깥 트랜잭션 안에서 부르면 던진다**([IllegalStateException]) — 결제사를 부르는 코드라 락 · 트랜잭션 밖이어야 한다
 * ([PaymentLedgerStore.hasOpenTransaction]). 큐 워커는 처리기를 트랜잭션으로 감싸지 않는다.
 */
class OrderExpireTaskHandler(
    private val store: PaymentLedgerStore,
    private val router: PaymentProviderRouter,
    private val convergence: PaymentConvergence,
) : PaymentTaskHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override val type: String = PaymentTaskTypes.ORDER_EXPIRE

    override fun handle(run: PaymentTaskRun) {
        store.requireNoOpenTransaction("The expiry task")
        val id = run.subjectId
        val order = store.findOrder(id) ?: return
        if (order.status != PaymentOrderStatus.PENDING) return
        val gateway = try {
            router.gateway(order.provider, PaymentLookupGateway::class)
        } catch (e: PaymentProviderNotFoundException) {
            log.warn("no payment provider bean to look up expiring order={} provider={}", id, order.provider)
            // 결제사가 없어도 `PENDING` 은 선점한 적이 없다 — 마감이 지났으면 닫는다(표시 없는 영구 `PENDING` 으로 두지 않는다). 마감 전이면 재시도
            closeWithoutLookup(id, ConvergeContext(order.providerMode, ConvergeSource.EXPIRE), "No payment provider is available to look up order $id")
            return
        } catch (e: PaymentCapabilityUnsupportedException) {
            throw PaymentTaskPermanentException("The payment provider of order $id cannot be looked up")
        }
        val context = ConvergeContext(gateway.mode, ConvergeSource.EXPIRE)
        if (gateway.mode != order.providerMode) {
            // `PENDING` 은 승인을 선점한 적이 없다 — 돈이 움직였을 수 없으니 표시도 경보도 없이 마감이 지나면 닫는다(다른 모드 키로는 물을 수도 없다).
            // 마감 전이면 큐가 나중에 다시 부르게 던진다(마감 뒤 다시 와서 닫힌다).
            closeWithoutLookup(id, context, "Order $id was created under another payment mode and its checkout is still open")
            return
        }
        val snapshot = try {
            gateway.lookup(PaymentLookupQuery(providerPaymentId = null, orderId = order.id))
        } catch (e: Exception) {
            log.warn("payment lookup failed for expiry order={} exception={}", id, e.javaClass.simpleName)
            throw PaymentTaskRetryException("Payment lookup is unavailable for order $id")
        }
        val settled = store.inTransaction { tx ->
            val locked = tx.lockOrder(id) ?: return@inTransaction null
            if (locked.status != PaymentOrderStatus.PENDING) return@inTransaction locked
            if (snapshot == null) convergence.convergeAbsent(tx, locked, context).order else convergence.converge(tx, locked, snapshot, context).order
        } ?: return
        if (settled.status != PaymentOrderStatus.PENDING) return
        if (settled.reviewReason in PERMANENT_REVIEWS) throw PaymentTaskPermanentException("Order $id needs a person to review it")
        throw PaymentTaskRetryException("Order $id is not settled yet")
    }

    /**
     * 결제사에 묻지 않고 닫는다(모드가 다르거나 결제사 빈이 없다) — 락 안에서 줄을 다시 읽고, 마감이 지났고 결제사가 "받았다"고 답한 표시가 없으면 `EXPIRED`.
     * 그대로 `PENDING` 이면 표시가 있으면 영구 실패, 없으면(마감 전) [stillOpen] 으로 재시도.
     */
    private fun closeWithoutLookup(id: String, context: ConvergeContext, stillOpen: String) {
        val closed = store.inTransaction { tx ->
            val locked = tx.lockOrder(id) ?: return@inTransaction null
            if (locked.status != PaymentOrderStatus.PENDING) locked else convergence.expireUnpaid(tx, locked, context).order
        } ?: return
        if (closed.status != PaymentOrderStatus.PENDING) return
        if (closed.reviewReason in PROVIDER_RECEIVED_MARKS) throw PaymentTaskPermanentException("Order $id needs a person to review it")
        throw PaymentTaskRetryException(stillOpen)
    }

    /**
     * 만료 작업이 죽어도 이 처리기가 표시하는 것은 없다 — 줄은 아직 `PENDING` 이라 승인을 선점한 적이 없고(전이표에 `CONFIRMING` → `PENDING` 이 없다) 우리 쪽에서 돈이 움직인 적이 없다.
     * 죽는 길마다: 모드가 달라도 · 결제사 빈이 없어도 표시하지 않는다(마감이 지나면 [handle] 이 `EXPIRED` 로 닫는다) · 결제사가 돈이 들어왔다고 말했는데 금액 · 주문이 어긋나면 [PaymentConvergence] 가 표시했다
     * (그 표시가 있는 줄은 조회 없는 길에서는 닫지 않는다) · 조회가 계속 안 되거나 결제가 계속 진행 중이면 **표시 없이** 남는다 — 열려 있는 결제창으로 사용자가 승인하면 그 길이 줄을 이어 가고, 작업이 죽은 사실은 `alert` 의 `JOB_DEAD` 와 작업 줄이 말한다.
     */
    override fun onDead(run: PaymentTaskRun) = Unit
}

/**
 * `payment-order-reconcile` — 승인을 선점할 때 넣은 작업. 줄이 아직 `CONFIRMING` 이면 결제 키로 결제사에 묻고 [PaymentConvergence.converge] 한다.
 * **결제사가 끝 상태를 말할 때까지 줄을 풀지 않는다**(던져서 큐가 다시 부른다). 마지막 시도에도 끝나지 않으면 `CONFIRM_UNRESOLVED` 를 표시하고
 * (큐가 죽은 작업으로 경보를 낸다).
 *
 * 영구 실패는 **먼저 줄에 표시한다** — 돈이 나갔을 수 있는 `CONFIRMING` 줄이 표시 없이 남으면 「확인 필요」 목록에 뜨지 않는다:
 * - 모드가 다르면 조회하기 전에 `MODE_MISMATCH` (다른 모드 키로 물으면 결제가 없다고 오거나 인증 오류가 와서 12번 헛돈다)
 * - 조회 능력이 없는 결제사는 `CONFIRM_UNRESOLVED`
 * - 금액 · 주문 불일치는 [PaymentConvergence] 가 표시한다
 * 결제사 **빈이 없는 것**은 설정으로 고쳐지는 상태라 영구가 아니다 — 재시도하고 마지막 시도에서 `CONFIRM_UNRESOLVED`.
 *
 * **바깥 트랜잭션 안에서 부르면 던진다**([IllegalStateException], [PaymentLedgerStore.hasOpenTransaction]) — 결제사를 부르는 코드다.
 */
class OrderReconcileTaskHandler(
    private val store: PaymentLedgerStore,
    private val router: PaymentProviderRouter,
    private val convergence: PaymentConvergence,
) : PaymentTaskHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override val type: String = PaymentTaskTypes.ORDER_RECONCILE

    override fun handle(run: PaymentTaskRun) {
        store.requireNoOpenTransaction("The reconcile task")
        val id = run.subjectId
        val order = store.findOrder(id) ?: return
        if (order.status != PaymentOrderStatus.CONFIRMING) return
        val gateway = try {
            lookupGatewayOrNull(router, order, log)
        } catch (e: PaymentCapabilityUnsupportedException) {
            val flagged = flagIfStillOpen(store, convergence, id, PaymentOrderStatus.CONFIRMING, PaymentReviewReason.CONFIRM_UNRESOLVED, ConvergeContext(order.providerMode, ConvergeSource.RECONCILE))
            if (flagged == null || flagged.status != PaymentOrderStatus.CONFIRMING) return
            throw PaymentTaskPermanentException("The payment provider of order $id cannot be looked up")
        }
        if (gateway != null && gateway.mode != order.providerMode) {
            val flagged = flagIfStillOpen(store, convergence, id, PaymentOrderStatus.CONFIRMING, PaymentReviewReason.MODE_MISMATCH, ConvergeContext(gateway.mode, ConvergeSource.RECONCILE))
            if (flagged == null || flagged.status != PaymentOrderStatus.CONFIRMING) return
            throw PaymentTaskPermanentException("Order $id was created under another payment mode")
        }
        var lookupFailed = gateway == null
        val snapshot = gateway?.let {
            try {
                it.lookup(PaymentLookupQuery(providerPaymentId = order.providerPaymentId, orderId = if (order.providerPaymentId == null) order.id else null))
            } catch (e: Exception) {
                log.warn("payment lookup failed for reconcile order={} attempt={}/{} exception={}", id, run.attempt, run.maxAttempts, e.javaClass.simpleName)
                lookupFailed = true
                null
            }
        }
        val context = ConvergeContext(gateway?.mode ?: order.providerMode, ConvergeSource.RECONCILE)
        val settled = store.inTransaction { tx ->
            val locked = tx.lockOrder(id) ?: return@inTransaction null
            if (locked.status != PaymentOrderStatus.CONFIRMING) return@inTransaction locked
            var after = if (snapshot != null) convergence.converge(tx, locked, snapshot, context).order else locked
            if (after.status == PaymentOrderStatus.CONFIRMING && after.reviewReason == null && run.lastAttempt) {
                after = convergence.flagReview(tx, after, PaymentReviewReason.CONFIRM_UNRESOLVED, context).order
            }
            after
        } ?: return
        if (settled.status != PaymentOrderStatus.CONFIRMING) return
        if (settled.reviewReason in PERMANENT_REVIEWS) throw PaymentTaskPermanentException("Order $id needs a person to review it")
        throw PaymentTaskRetryException(if (lookupFailed) "Payment lookup is unavailable for order $id" else "Order $id is not settled yet")
    }

    /**
     * 조회 작업이 죽었다 — 줄이 아직 `CONFIRMING` 이고 표시가 없으면 `CONFIRM_UNRESOLVED`(「확인 필요」 목록의 마지막 안전망).
     * 위 [handle] 의 마지막 시도 처리가 못 닿는 길을 메운다: 워커가 죽어 멈춘 채 시도를 다 쓴 작업(처리기를 거치지 않는다) · 마지막 시도에서 `findOrder`/`inTransaction` 이 DB 오류로 던진 작업.
     * 이미 끝난 줄 · 이미 표시가 있는 줄(먼저 찍힌 이유가 남는다)은 건드리지 않는다 — 두 번 불려도 한 번.
     */
    override fun onDead(run: PaymentTaskRun) {
        store.inTransaction { tx ->
            val locked = tx.lockOrder(run.subjectId) ?: return@inTransaction
            if (locked.status != PaymentOrderStatus.CONFIRMING) return@inTransaction
            convergence.flagReview(tx, locked, PaymentReviewReason.CONFIRM_UNRESOLVED, ConvergeContext(locked.providerMode, ConvergeSource.RECONCILE))
        }
    }
}

/** `payment-order-fulfil` — 락 안에서 `fulfilled_at` 이 비었으면 리스너를 한 번. 리스너 예외의 문장은 싣지 않는다(우리 문장과 id 뿐). */
class OrderFulfilTaskHandler(
    private val fulfillment: PaymentFulfillment,
    private val store: PaymentLedgerStore,
    private val convergence: PaymentConvergence,
) : PaymentTaskHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override val type: String = PaymentTaskTypes.ORDER_FULFIL

    override fun handle(run: PaymentTaskRun) {
        try {
            fulfillment.fulfil(run.subjectId)
        } catch (e: Exception) {
            log.warn("payment fulfilment failed order={} attempt={}/{} exception={}", run.subjectId, run.attempt, run.maxAttempts, e.javaClass.simpleName)
            throw PaymentTaskRetryException("Fulfilment failed for order ${run.subjectId}")
        }
    }

    /**
     * 지급 작업이 죽었다 — 주문이 아직 지급 전(`PAID` · `PARTIALLY_REFUNDED` 이고 `fulfilled_at` 이 NULL)이면 `FULFILMENT_UNRESOLVED` 를 찍는다(돈은 받았는데 지급이 안 됐다, 스펙 §15 의 「확인 필요」 탭).
     * 락 안에서 다시 읽는다(그 사이 지급됐을 수 있다). 지급 전에 전액 환불된 주문(`REFUNDED`)은 지급을 건너뛰는 것이 정상이라 찍지 않는다. 돈 쪽 이유가 이미 있으면 덮지 않는다
     * ([PaymentReviewReason.overrides]). 두 번 불려도 한 번. 닫는 길은 운영자의 「결제사와 맞추기」 — 지급 작업을 다시 넣고 이 표시를 푼다.
     */
    override fun onDead(run: PaymentTaskRun) {
        store.inTransaction { tx ->
            val locked = tx.lockOrder(run.subjectId) ?: return@inTransaction
            if (!(locked.status == PaymentOrderStatus.PAID || locked.status == PaymentOrderStatus.PARTIALLY_REFUNDED) || locked.fulfilledAt != null) return@inTransaction
            convergence.flagReview(tx, locked, PaymentReviewReason.FULFILMENT_UNRESOLVED, ConvergeContext(locked.providerMode, ConvergeSource.RECONCILE))
        }
    }
}

/** 처리기를 모아 종류로 나눠 준다. 같은 종류가 둘이면 기동 실패. */
class DefaultPaymentTaskRunner(handlers: List<PaymentTaskHandler>) : PaymentTaskRunner {
    private val byType: Map<String, PaymentTaskHandler>

    init {
        val duplicate = handlers.groupBy { it.type }.entries.firstOrNull { it.value.size > 1 }
        require(duplicate == null) { "Duplicate payment task handler for type '${duplicate!!.key}'" }
        byType = handlers.associateBy { it.type }
    }

    override val types: Set<String> get() = byType.keys

    override fun run(run: PaymentTaskRun) {
        val handler = byType[run.type] ?: throw PaymentTaskPermanentException("No handler is registered for payment task type '${run.type}'")
        handler.handle(run)
    }

    override fun onDead(run: PaymentTaskRun) {
        byType[run.type]?.onDead(run)
    }
}
