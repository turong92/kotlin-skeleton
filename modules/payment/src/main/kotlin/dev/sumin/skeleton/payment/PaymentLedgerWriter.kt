package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.time.TimeProvider
import org.slf4j.MDC

/**
 * 주문 줄을 바꾸고 이력을 남기는 곳 — **상태를 바꾸는 코드는 모두 [transition] 을 거친다**(전이표 [PaymentOrderTransitions] 강제).
 * 줄을 바꾸려면 호출하는 쪽이 [PaymentLedgerTx.lockOrder] 로 잠근 줄을 넘겨야 한다(포트가 잠그지 않은 줄의 쓰기를 거절한다).
 */
class PaymentLedgerWriter(private val time: TimeProvider) {
    fun now() = time.now()

    /** 상태를 [to] 로 옮기고 [mutate] 로 나머지 칸을 바꾼 뒤 저장하고 이력 한 줄을 남긴다. 표에 없는 전이는 [IllegalStateException]. */
    fun transition(
        tx: PaymentLedgerTx,
        order: PaymentOrder,
        to: PaymentOrderStatus,
        type: PaymentEventType,
        actor: PaymentActor,
        actorId: String? = null,
        providerCode: String? = null,
        refundId: String? = null,
        mutate: (PaymentOrder) -> PaymentOrder = { it },
    ): PaymentOrder {
        PaymentOrderTransitions.require(order.status, to)
        val changed = mutate(order).copy(status = to, updatedAt = now())
        tx.updateOrder(changed)
        tx.appendEvent(event(order.id, type, actor, actorId, providerCode, order.status, to, refundId))
        return changed
    }

    /**
     * 환불이 결제사 증거로 되돌려졌다(차지백 분쟁에서 이김) - 주문이 `REFUNDED`/`PARTIALLY_REFUNDED` 에서 환불된 금액이 줄어든 상태로 간다. 전이표에 없는 길이라
     * [PaymentOrderTransitions.requireByProviderEvidence] 가 따로 검사한다 - 이 함수만 쓴다.
     */
    fun reverseByProviderEvidence(
        tx: PaymentLedgerTx,
        order: PaymentOrder,
        to: PaymentOrderStatus,
        type: PaymentEventType,
        actor: PaymentActor,
        providerCode: String? = null,
        refundId: String? = null,
        mutate: (PaymentOrder) -> PaymentOrder = { it },
    ): PaymentOrder {
        PaymentOrderTransitions.requireByProviderEvidence(order.status, to)
        val changed = mutate(order).copy(status = to, updatedAt = now())
        tx.updateOrder(changed)
        tx.appendEvent(event(order.id, type, actor, null, providerCode, order.status, to, refundId))
        return changed
    }

    /** 상태는 그대로 두고 다른 칸만 바꾼다(검토 표시 · 지급 시각). 이력 한 줄을 남긴다. */
    fun touch(
        tx: PaymentLedgerTx,
        order: PaymentOrder,
        type: PaymentEventType,
        actor: PaymentActor,
        actorId: String? = null,
        providerCode: String? = null,
        refundId: String? = null,
        mutate: (PaymentOrder) -> PaymentOrder,
    ): PaymentOrder {
        val changed = mutate(order).copy(status = order.status, updatedAt = now())
        tx.updateOrder(changed)
        tx.appendEvent(event(order.id, type, actor, actorId, providerCode, order.status, order.status, refundId))
        return changed
    }

    /**
     * 환불 줄의 상태를 [to] 로 옮기고 [mutate] 로 나머지 칸을 바꾼 뒤 저장하고 이력 한 줄을 남긴다(이력의 `from/to` 는 **환불 줄의** 상태).
     * 표([PaymentRefundTransitions])에 없는 전이는 [IllegalStateException]. `open_key` 는 새 상태가 정한다 — 열려 있으면 주문 id, 닫히면 null.
     * 호출하는 쪽이 주문을 잠갔어야 한다(포트가 확인한다).
     */
    fun transitionRefund(
        tx: PaymentLedgerTx,
        refund: PaymentRefund,
        to: PaymentRefundStatus,
        type: PaymentEventType,
        actor: PaymentActor,
        actorId: String? = null,
        providerCode: String? = null,
        mutate: (PaymentRefund) -> PaymentRefund = { it },
    ): PaymentRefund {
        PaymentRefundTransitions.require(refund.status, to)
        val changed = mutate(refund).copy(status = to, openKey = PaymentRefundTransitions.openKeyFor(to, refund.orderId), updatedAt = now())
        tx.updateRefund(changed)
        tx.appendEvent(refundEvent(refund.orderId, refund.id, type, actor, actorId, providerCode, refund.status, to))
        return changed
    }

    /**
     * 닫힌(`FAILED`) 환불 줄이 결제사 증거로 `SUCCEEDED` 가 된다([PaymentRefundTransitions.isAllowedByProviderEvidence]). 이력은 환불 줄의 `FAILED → SUCCEEDED` 한 줄(이유 [providerCode])이다.
     * 호출하는 쪽이 주문을 잠갔고 합계가 넘치지 않음을 확인했어야 한다 - 주문 쪽 반영은 [RefundLines.revive].
     */
    fun reviveRefund(tx: PaymentLedgerTx, refund: PaymentRefund, actor: PaymentActor, actorId: String?, providerCode: String, mutate: (PaymentRefund) -> PaymentRefund): PaymentRefund {
        check(PaymentRefundTransitions.isAllowedByProviderEvidence(refund.status, PaymentRefundStatus.SUCCEEDED)) { "Refund status ${refund.status} cannot be revived by provider evidence" }
        val changed = mutate(refund).copy(status = PaymentRefundStatus.SUCCEEDED, openKey = PaymentRefundTransitions.openKeyFor(PaymentRefundStatus.SUCCEEDED, refund.orderId), updatedAt = now())
        tx.updateRefund(changed)
        tx.appendEvent(refundEvent(refund.orderId, refund.id, PaymentEventType.REFUND_SUCCEEDED, actor, actorId, providerCode, refund.status, PaymentRefundStatus.SUCCEEDED))
        return changed
    }

    /** 새 환불 줄을 넣는다(상태는 [PaymentRefundTransitions.requireCreatable]). 넣은 줄(`open_key` 포함)을 돌려주고, 겹치는 유니크가 있으면 null 이며 이력도 남기지 않는다. */
    fun createRefund(
        tx: PaymentLedgerTx,
        refund: PaymentRefund,
        type: PaymentEventType,
        actor: PaymentActor,
        actorId: String? = null,
    ): PaymentRefund? {
        PaymentRefundTransitions.requireCreatable(refund.status)
        val line = refund.copy(openKey = PaymentRefundTransitions.openKeyFor(refund.status, refund.orderId))
        if (!tx.insertRefundIfAbsent(line)) return null
        tx.appendEvent(refundEvent(line.orderId, line.id, type, actor, actorId, null, null, line.status))
        return line
    }

    /** 환불 줄 이력 한 줄 — `from/to` 는 환불 줄의 상태. */
    fun refundEvent(
        orderId: String,
        refundId: String,
        type: PaymentEventType,
        actor: PaymentActor,
        actorId: String? = null,
        providerCode: String? = null,
        from: PaymentRefundStatus? = null,
        to: PaymentRefundStatus? = null,
    ): PaymentEvent = event(orderId, type, actor, actorId, providerCode, refundId = refundId).copy(fromStatus = from?.name, toStatus = to?.name)

    fun event(
        orderId: String,
        type: PaymentEventType,
        actor: PaymentActor,
        actorId: String? = null,
        providerCode: String? = null,
        from: PaymentOrderStatus? = null,
        to: PaymentOrderStatus? = null,
        refundId: String? = null,
    ): PaymentEvent = PaymentEvent(
        orderId = orderId,
        type = type,
        actor = actor,
        createdAt = now(),
        refundId = refundId,
        fromStatus = from?.name,
        toStatus = to?.name,
        actorId = actorId,
        providerCode = providerCode,
        traceId = MDC.get("traceId")?.take(64),
    )
}
