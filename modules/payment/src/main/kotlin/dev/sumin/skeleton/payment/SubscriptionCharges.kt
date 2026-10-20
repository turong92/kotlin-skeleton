package dev.sumin.skeleton.payment

import org.slf4j.LoggerFactory

/** 빌링 승인 한 번의 끝. */
sealed interface ChargeOutcome {
    /** 결제사가 받았다고 답했고 장부가 `PAID` 로 확정했다(지급 고리까지 한 번 시도했다). */
    data object Paid : ChargeOutcome

    /** 결제사가 **돈이 움직이지 않았다**고 확정 거절했다 — 주문은 `FAILED` 로 닫혔다. */
    data class Declined(val providerCode: String?) : ChargeOutcome

    /** 모른다 — 줄은 `CONFIRMING` 그대로이고 조회 작업이 끝낸다. **다시 보내지 않는다.** */
    data object Unknown : ChargeOutcome
}

/**
 * 이미 선점한(`CONFIRMING` + 조회 작업이 커밋된) 구독 주문 하나를 빌링키로 승인한다 — 첫 결제와 갱신이 같은 걸음을 쓴다(스펙 §5 의 3 · 4).
 * **결제사 호출은 락 · 트랜잭션 밖이다**(바깥 트랜잭션 안이면 던진다). 모름이면 다시 보내지 않는다 — 기존 조회 작업이 주문 번호로 결제사에 물어 끝낸다.
 */
class SubscriptionCharges(
    private val store: PaymentLedgerStore,
    private val convergence: PaymentConvergence,
    private val fulfillment: PaymentFulfillment,
    private val writer: PaymentLedgerWriter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * @param token 복호화한 빌링키(호출 동안만 메모리에 있다)
     * 확정 거절이면 주문이 `FAILED` 가 되는 같은 트랜잭션에서 구독이 반응한다([PaymentConvergence.subscriptions] → [SubscriptionLifecycle.onOrderClosed]).
     */
    fun charge(
        gateway: PaymentBillingGateway,
        subscription: PaymentSubscription,
        order: PaymentOrder,
        token: String,
        actor: PaymentActor,
    ): ChargeOutcome {
        store.requireNoOpenTransaction("Charging a subscription")
        val context = ConvergeContext(gateway.mode, ConvergeSource.CONFIRM, actor, null)
        val result = try {
            gateway.charge(
                BillingChargeRequest(
                    token = token,
                    customerKey = checkNotNull(subscription.customerKey) { "a subscription that is charged has a customer key" },
                    orderId = order.id,
                    orderName = order.orderName,
                    amount = order.amount,
                    idempotencyKey = IdempotencyKey(order.id), // 같은 주문의 재전송이 생겨도 결제사가 한 번으로 본다
                ),
            )
        } catch (e: Exception) {
            // 호출 자체의 실패만 여기서 가른다 — 확정(저장) 쪽 버그를 "모름"으로 삼키지 않으려고 settle 은 try 밖이다
            return when (PaymentFailures.outcomeOf(e)) {
                PaymentFailureOutcome.DEFINITIVE -> decline(order, PaymentFailures.providerCodeOf(e), context)
                PaymentFailureOutcome.UNKNOWN -> unknown(order, e, context)
            }
        }
        val settled = settle(order, result, context) ?: return unknown(order, null, context, result)
        if (settled.status.isPaidFamily) fulfillment.tryFulfil(settled.id)
        return if (settled.status.isPaidFamily) ChargeOutcome.Paid else ChargeOutcome.Unknown
    }

    /**
     * **등록이 곧 첫 결제**인 결제사(카카오페이)의 첫 걸음 - 선점한 첫 주문을 [PaymentBillingGateway.registerWithCharge] 로 승인하고, 받았으면 **같은 트랜잭션에서** 토큰(SID, [onToken] 이 암호화한다) ·
     * 카드 표시를 구독 줄에 쓴다. 토큰을 못 읽었으면([RegisteredCharge.issue] 가 null) 구독은 켜지되 토큰이 비어 있다. 돈이 움직이는 호출이라 실패 분류는 [charge] 와 같다(확정 거절 → 구독 종료 / 모름 → 다시 보내지 않고 조회).
     */
    fun registerAndCharge(
        gateway: PaymentBillingGateway,
        subscription: PaymentSubscription,
        order: PaymentOrder,
        request: RegisterWithChargeRequest,
        actor: PaymentActor,
        onToken: (PaymentSubscription, BillingIssue) -> PaymentSubscription,
    ): ChargeOutcome {
        store.requireNoOpenTransaction("Registering a subscription with its first charge")
        val context = ConvergeContext(gateway.mode, ConvergeSource.CONFIRM, actor, null)
        val registered = try {
            gateway.registerWithCharge(request)
        } catch (e: Exception) {
            return when (PaymentFailures.outcomeOf(e)) {
                PaymentFailureOutcome.DEFINITIVE -> decline(order, PaymentFailures.providerCodeOf(e), context)
                PaymentFailureOutcome.UNKNOWN -> unknown(order, e, context)
            }
        }
        val settled = settle(order, registered.result, context) { tx, paid ->
            val issued = registered.issue
            if (issued != null && paid.status.isPaidFamily) tx.lockSubscription(subscription.id)?.let { locked -> tx.updateSubscription(onToken(locked, issued)) }
            // 돈은 받았는데 빌링키를 못 읽었다 - 구독은 켜지되 갱신할 수단이 없다. 갱신일에 조용히 끝나지 않게 지금 운영자에게 표시한다
            else if (issued == null && paid.status.isPaidFamily) flagMissingToken(tx, subscription.providerMode, paid)
        } ?: return unknown(order, null, context, registered.result)
        if (settled.status.isPaidFamily) fulfillment.tryFulfil(settled.id)
        return if (settled.status.isPaidFamily) ChargeOutcome.Paid else ChargeOutcome.Unknown
    }

    /** 승인 응답이 `CONFIRMED` 이고 총액이 있을 때만 해석한다 — 그 밖은 모름. 응답의 결제 키가 곧 이 주문의 결제 키다(선점 때는 아직 없다). */
    private fun settle(order: PaymentOrder, result: PaymentOperationResult, context: ConvergeContext, inTransaction: ((PaymentLedgerTx, PaymentOrder) -> Unit)? = null): PaymentOrder? {
        if (result.status != PaymentOperationStatus.CONFIRMED) return null
        val total = result.amount ?: return null
        val snapshot = PaymentSnapshot(
            providerPaymentId = result.providerPaymentId,
            orderId = result.trace.metadata["orderId"],
            status = ProviderPaymentStatus.PAID,
            total = total,
            remaining = result.remaining ?: total,
            method = result.attributes["method"],
            paidAt = result.paidAt,
            refunds = emptyList(),
            card = result.card,
        )
        return store.inTransaction { tx ->
            val locked = tx.lockOrder(order.id) ?: return@inTransaction order
            if (locked.status != PaymentOrderStatus.CONFIRMING) return@inTransaction locked // 그 사이 조회 작업이 먼저 끝냈다
            convergence.converge(tx, locked, snapshot, context).order.also { inTransaction?.invoke(tx, it) }
        }
    }

    /** 첫 결제는 받았는데 구독에 빌링키가 없다 - 첫 주문에 `SUBSCRIPTION_TOKEN_MISSING` 을 표시한다(이미 다른 표시가 있으면 그대로). */
    fun flagMissingToken(tx: PaymentLedgerTx, mode: PaymentMode, initialOrder: PaymentOrder) {
        convergence.flagReview(tx, initialOrder, PaymentReviewReason.SUBSCRIPTION_TOKEN_MISSING, ConvergeContext(mode, ConvergeSource.CONFIRM))
    }

    /** 갱신일에 빌링키 없는 구독을 만났다 - 첫 주문(결정적 id)에 표시를 남긴다. 첫 주문이 없으면 로그뿐. */
    fun flagMissingToken(tx: PaymentLedgerTx, subscription: PaymentSubscription) {
        val initial = tx.lockOrder(PaymentOrderIds.initialOrderId(subscription.id)) ?: return
        flagMissingToken(tx, subscription.providerMode, initial)
    }

    private fun decline(order: PaymentOrder, providerCode: String?, context: ConvergeContext): ChargeOutcome {
        val closed = store.inTransaction { tx ->
            val locked = tx.lockOrder(order.id) ?: return@inTransaction order
            convergence.declined(tx, locked, providerCode, context).order
        }
        // 그 사이 다른 길이 먼저 끝냈다면(늦은 거절은 받은 돈을 되돌리지 못한다) 거절이 아니다
        return if (closed.status == PaymentOrderStatus.FAILED) ChargeOutcome.Declined(closed.failureCode) else ChargeOutcome.Unknown
    }

    /** 모름 — 줄의 상태를 바꾸지 않는다(이력 한 줄만). 조회 작업이 결제사가 끝 상태를 말할 때까지 묻는다. */
    private fun unknown(order: PaymentOrder, cause: Exception?, context: ConvergeContext, result: PaymentOperationResult? = null): ChargeOutcome {
        log.warn("subscription charge outcome unknown order={} exception={}", order.id, cause?.javaClass?.simpleName ?: "unreadable response")
        // 모르는 답이어도 결제사가 결제 번호를 줬으면(네이버: 예약으로 받은 paymentId) 주문의 결제 키 자리에 박는다 - 조회가 그 번호로 끝낸다
        val key = result?.takeIf { it.status == PaymentOperationStatus.UNKNOWN }?.providerPaymentId?.let(LedgerValues::code)
        if (key != null) {
            try {
                store.inTransaction { tx ->
                    val locked = tx.lockOrder(order.id)
                    if (locked != null && locked.status == PaymentOrderStatus.CONFIRMING && locked.providerPaymentId == null) tx.updateOrder(locked.copy(providerPaymentId = key, updatedAt = writer.now()))
                }
            } catch (e: PaymentLedgerConflictException) {
                log.warn("the payment key of an unconfirmed subscription charge was already taken order={}", order.id) // 이 트랜잭션은 되돌려졌다 - 아래 이력은 따로 적는다
            }
        }
        store.inTransaction { tx ->
            tx.appendEvent(writer.event(order.id, PaymentEventType.CONFIRM_UNKNOWN, context.actor, context.actorId, from = order.status, to = order.status))
        }
        return ChargeOutcome.Unknown
    }
}
