package dev.sumin.skeleton.payment

/** 맞추기를 부른 곳. `EXPIRE` 만 `PENDING` 을 닫을 수 있다(그 밖에서는 아직 결제 중일 수 있다). */
enum class ConvergeSource { CONFIRM, RECONCILE, EXPIRE, WEBHOOK, SYNC, REFUND }

/**
 * [currentMode] 는 **지금** 끼워진 결제사의 모드 — 줄에 박힌 모드와 다르면 아무것도 바꾸지 않고 `MODE_MISMATCH` 만 표시한다.
 * **예외**: 승인을 선점한 적 없는 `PENDING` 은 표시하지 않는다(돈이 움직였을 수 없다) — 만료만 [PaymentConvergence.expireUnpaid] 로 결제사에 묻지 않고 닫는다.
 */
data class ConvergeContext(
    val currentMode: PaymentMode,
    val source: ConvergeSource,
    val actor: PaymentActor = PaymentActor.SYSTEM,
    val actorId: String? = null,
    /** 스냅샷을 얻으려고 결제사에 묻기 **시작한** 시각 — 그 뒤에 바뀐 환불 줄이 있으면 스냅샷이 낡았을 수 있어 환불 남은 금액 대조를 건너뛴다. */
    val snapshotAt: java.time.Instant? = null,
)

data class ConvergeResult(val order: PaymentOrder, val changed: Boolean)

/** 스펙 §6.1 의 4 · 5(환불 맞추기) 자리. B5 가 구현한다 — 기본은 아무것도 하지 않는다. 바뀐 줄을 돌려준다. */
fun interface PaymentRefundConvergence {
    fun converge(tx: PaymentLedgerTx, order: PaymentOrder, snapshot: PaymentSnapshot, context: ConvergeContext): PaymentOrder

    companion object {
        val NONE: PaymentRefundConvergence = PaymentRefundConvergence { _, order, _, _ -> order }
    }
}

/**
 * **결제사와 장부를 맞추는 코드는 여기 한 곳이다**(스펙 §6.1 원칙 3): 승인 응답의 확정 · 조회 작업 · 웹훅 · 운영자의 「결제사와 맞추기」 · 환불 전 확인이
 * 모두 이 함수로 끝난다. 호출하는 쪽이 주문 줄을 **잠그고 다시 읽은 값**을 넘긴다(같은 트랜잭션 안). 몇 번 불려도 결과가 같다 —
 * 바뀐 것이 없으면 줄도 이력도 작업도 건드리지 않는다.
 *
 * 1. 줄의 모드와 지금 결제사의 모드가 다르면 아무것도 바꾸지 않고 `MODE_MISMATCH`.
 * 2. 스냅샷이 "받았다"인데 줄이 아직 `PAID` 이후가 아니면: 결제 키 · 주문 번호가 가리키는 주문이고 총액 · 통화가 줄과 같을 때만 `PAID`(+지급 작업). 아니면 검토 표시만.
 * 3. 스냅샷이 실패 · 만료이고 줄이 `CONFIRMING`(또는 만료 작업이 부른 `PENDING`)이면 닫는다. 줄이 이미 `PAID` 이후면 무시한다.
 * 4 · 5. 환불 맞추기 — [PaymentRefundConvergence] 자리(B5).
 * 6. 바뀐 것이 있으면 이력 한 줄(바꾸는 곳마다 [PaymentLedgerWriter] 가 남긴다).
 */
class PaymentConvergence(
    private val writer: PaymentLedgerWriter,
    private val tasks: PaymentTasks,
    private val review: PaymentReviewNotifier = PaymentReviewNotifier.NONE,
    private val refunds: PaymentRefundConvergence = PaymentRefundConvergence.NONE,
) {
    private val flags = PaymentReviewFlags(writer, review)

    /** 구독을 켠 조립에서만 — 구독 주문이 끝 상태(`FAILED`)가 되는 **같은 트랜잭션**에서 구독이 반응한다([SubscriptionLifecycle.onOrderClosed]). */
    var subscriptions: SubscriptionLifecycle? = null

    fun converge(tx: PaymentLedgerTx, order: PaymentOrder, snapshot: PaymentSnapshot, context: ConvergeContext): ConvergeResult {
        if (order.providerMode != context.currentMode) return result(order, flag(tx, order, PaymentReviewReason.MODE_MISMATCH, context))
        val afterPayment = when (snapshot.status) {
            ProviderPaymentStatus.PAID, ProviderPaymentStatus.PARTIALLY_REFUNDED, ProviderPaymentStatus.REFUNDED -> applyReceived(tx, order, snapshot, context)
            ProviderPaymentStatus.FAILED, ProviderPaymentStatus.EXPIRED -> applyClosed(tx, order, snapshot, context)
            ProviderPaymentStatus.IN_PROGRESS -> order
        }
        return result(order, refunds.converge(tx, afterPayment, snapshot, context))
    }

    /** 결제사에 그 결제가 **없다**(조회가 null). 만료 작업만 `PENDING` 을 닫을 수 있다. */
    fun convergeAbsent(tx: PaymentLedgerTx, order: PaymentOrder, context: ConvergeContext): ConvergeResult {
        if (order.providerMode != context.currentMode) return result(order, flag(tx, order, PaymentReviewReason.MODE_MISMATCH, context))
        return result(order, expireIfAsked(tx, order, context))
    }

    /**
     * 승인을 선점한 적 없는 `PENDING` 을 결제사에 묻지 않고 `EXPIRED` 로 닫는다(모드가 다르거나 결제사 빈이 없는 줄 — 만료 작업만) — 표시 · 경보는 없다.
     * **결제 마감(`checkout_expires_at`) 전이면 닫지 않는다**(그대로 돌려주면 작업이 다시 온다). 이미 `PENDING` 이 아니면 그대로.
     * **결제사가 받았다고 답한 적이 있는 줄**(`AMOUNT_MISMATCH` · `ORDER_MISMATCH` 표시)은 닫지 않는다 — 조회 없는 길에서는 닫지 않는다(같은 모드 경로는 결제사가 이번에 「결제 없음 · 실패 · 만료」로 답하면 표시를 단 채 닫는다). 사람이 본다.
     */
    fun expireUnpaid(tx: PaymentLedgerTx, order: PaymentOrder, context: ConvergeContext): ConvergeResult {
        if (order.reviewReason in PROVIDER_RECEIVED_MARKS) return ConvergeResult(order, changed = false)
        if (writer.now().isBefore(order.checkoutExpiresAt)) return ConvergeResult(order, changed = false)
        return result(order, expireIfAsked(tx, order, context))
    }

    /** 결제사가 **돈이 움직이지 않았다**고 확정해서 거절했다 — `CONFIRMING` 만 `FAILED` 로 닫는다(이미 끝난 줄은 그대로). */
    fun declined(tx: PaymentLedgerTx, order: PaymentOrder, providerCode: String?, context: ConvergeContext): ConvergeResult {
        if (order.status != PaymentOrderStatus.CONFIRMING) return ConvergeResult(order, changed = false)
        val code = LedgerValues.code(providerCode) ?: "DECLINED"
        val failed = writer.transition(tx, order, PaymentOrderStatus.FAILED, PaymentEventType.CONFIRM_DECLINED, context.actor, context.actorId, code) {
            it.copy(failureCode = code, closedAt = writer.now(), reviewReason = it.reviewReason.unlessConfirmUnresolved())
        }
        notifyClosed(tx, failed)
        return ConvergeResult(failed, changed = true)
    }

    private fun notifyClosed(tx: PaymentLedgerTx, order: PaymentOrder) {
        if (order.originType == PaymentOriginType.SUBSCRIPTION) subscriptions?.onOrderClosed(tx, order)
    }

    /** 사람이 봐야 한다고 줄에 표시한다. 이미 표시가 있으면 그대로(먼저 찍힌 이유가 남는다). 경보는 처음 찍힐 때 한 번. */
    fun flagReview(tx: PaymentLedgerTx, order: PaymentOrder, reason: PaymentReviewReason, context: ConvergeContext): ConvergeResult =
        result(order, flag(tx, order, reason, context))

    private fun applyReceived(tx: PaymentLedgerTx, order: PaymentOrder, snapshot: PaymentSnapshot, context: ConvergeContext): PaymentOrder {
        if (order.status.isPaidFamily) return order
        val pointsElsewhere = (snapshot.orderId != null && snapshot.orderId != order.id) ||
            (order.providerPaymentId != null && order.providerPaymentId != snapshot.providerPaymentId)
        if (pointsElsewhere) return flag(tx, order, PaymentReviewReason.ORDER_MISMATCH, context)
        if (!snapshot.total.sameAs(order.amount)) return flag(tx, order, PaymentReviewReason.AMOUNT_MISMATCH, context)

        val type = when {
            context.source == ConvergeSource.CONFIRM -> PaymentEventType.CONFIRM_SUCCEEDED
            order.status.isClosed -> PaymentEventType.REOPENED_PAID
            else -> PaymentEventType.CONVERGED
        }
        val paid = writer.transition(tx, order, PaymentOrderStatus.PAID, type, context.actor, context.actorId) { current ->
            current.copy(
                providerPaymentId = current.providerPaymentId ?: snapshot.providerPaymentId,
                paidAmount = snapshot.total.amount,
                paidAt = snapshot.paidAt ?: writer.now(),
                method = LedgerValues.method(snapshot.method),
                // 카드 두 칸은 PAID 가 될 때 한 번 채운다. 탈퇴한 계정의 줄에는 쓰지 않는다 — 나중에 PAID 가 되어도 지운 개인정보가 되살아나지 않게(스펙 §8.2)
                cardIssuer = if (current.accountErasedAt == null) LedgerValues.cardIssuer(snapshot.card?.issuer) else null,
                cardLast4 = if (current.accountErasedAt == null) LedgerValues.cardLast4(snapshot.card?.last4) else null,
                failureCode = null,
                closedAt = null,
                reviewReason = null,
            )
        }
        tasks.enqueue(PaymentTask(PaymentTaskTypes.ORDER_FULFIL, order.id))
        return paid
    }

    private fun applyClosed(tx: PaymentLedgerTx, order: PaymentOrder, snapshot: PaymentSnapshot, context: ConvergeContext): PaymentOrder =
        when (order.status) {
            PaymentOrderStatus.CONFIRMING -> {
                val code = if (snapshot.status == ProviderPaymentStatus.EXPIRED) "PROVIDER_EXPIRED" else "PROVIDER_ABORTED"
                writer.transition(tx, order, PaymentOrderStatus.FAILED, PaymentEventType.CONVERGED, context.actor, context.actorId, code) {
                    it.copy(failureCode = code, closedAt = writer.now(), reviewReason = it.reviewReason.unlessConfirmUnresolved())
                }.also { notifyClosed(tx, it) }
            }
            PaymentOrderStatus.PENDING -> expireIfAsked(tx, order, context)
            else -> order // 이미 PAID 이후거나 닫힌 줄 — 늦게 온 실패 신호는 무시한다
        }

    private fun expireIfAsked(tx: PaymentLedgerTx, order: PaymentOrder, context: ConvergeContext): PaymentOrder {
        if (order.status != PaymentOrderStatus.PENDING || context.source != ConvergeSource.EXPIRE) return order
        return writer.transition(tx, order, PaymentOrderStatus.EXPIRED, PaymentEventType.EXPIRED, context.actor, context.actorId) {
            // 닫히는 `PENDING` 은 선점한 적이 없다 — 앞서 찍힌 `MODE_MISMATCH`(이전 버전 · 다른 길)는 풀 길이 없으니 함께 비운다. 돈이 어긋난 표시는 위에서 이미 닫지 않았다
            it.copy(
                failureCode = EXPIRED_UNPAID,
                closedAt = writer.now(),
                reviewReason = it.reviewReason.unlessConfirmUnresolved().takeUnless { reason -> reason == PaymentReviewReason.MODE_MISMATCH },
            )
        }
    }

    /**
     * 주문이 `FAILED` · `EXPIRED` 로 **닫히면 돈이 안 나간 것으로 확정**된 것이다 — "확인 중이었는데 끝내 못 알았다"는 표시(`CONFIRM_UNRESOLVED`)는 그 의문이 풀렸으므로 비운다.
     * 안 비우면 닫힌 주문이 「확인 필요」에 영구히 남는다. 다른 이유(돈이 어긋남 · 모드)는 그대로 둔다.
     */
    private fun PaymentReviewReason?.unlessConfirmUnresolved(): PaymentReviewReason? = if (this == PaymentReviewReason.CONFIRM_UNRESOLVED) null else this

    private fun flag(tx: PaymentLedgerTx, order: PaymentOrder, reason: PaymentReviewReason, context: ConvergeContext): PaymentOrder =
        flags.flag(tx, order, reason, context)

    private fun result(before: PaymentOrder, after: PaymentOrder) = ConvergeResult(after, changed = after != before)

    private companion object {
        const val EXPIRED_UNPAID = "EXPIRED_UNPAID"
    }
}

/** `PENDING` 에 이 표시가 있으면 결제사가 "받았다"고 답한 적이 있다는 뜻이다 — 만료가 결제사 없이 닫아서는 안 된다. */
internal val PROVIDER_RECEIVED_MARKS: Set<PaymentReviewReason> = setOf(PaymentReviewReason.AMOUNT_MISMATCH, PaymentReviewReason.ORDER_MISMATCH)

/**
 * "사람이 봐야 한다"를 줄에 찍는 한 곳. 칸이 하나라 **우선순위**를 따른다([PaymentReviewReason.overrides]): 이미 표시가 있으면 그대로 두되(먼저 찍힌 이유가 남는다),
 * 돈이 어긋난 이유가 지급 쪽 이유 위에 오면 덮어쓴다 — 이력에 `REPLACED_<앞선 이유>` 한 줄을 더 남기고 경보를 다시 낸다. 경보는 새로 찍힐 때마다 한 번.
 * **"방금 찍었는가"의 판정을 이 칸에 기대지 않는다** — 칸을 다른 이유가 차지하고 있으면 찍히지 않는다(환불 전 확인은 맞추기의 판정 값으로 가른다).
 */
internal class PaymentReviewFlags(private val writer: PaymentLedgerWriter, private val review: PaymentReviewNotifier) {
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    fun flag(tx: PaymentLedgerTx, order: PaymentOrder, reason: PaymentReviewReason, context: ConvergeContext): PaymentOrder {
        val existing = order.reviewReason
        if (existing != null && !reason.overrides(existing)) return order
        val flagged = writer.touch(tx, order, PaymentEventType.REVIEW_FLAGGED, context.actor, context.actorId, reason.name) {
            it.copy(reviewReason = reason)
        }
        if (existing != null) {
            tx.appendEvent(writer.event(order.id, PaymentEventType.REVIEW_FLAGGED, context.actor, context.actorId, REPLACED_PREFIX + existing.name, order.status, order.status))
        }
        log.warn("payment order needs review order={} reason={}", order.id, reason)
        review.flagged(order.id, reason)
        return flagged
    }

    companion object {
        /** 이력의 `provider_code`: 이 표시가 앞서 찍힌 이유를 덮어썼다(`REPLACED_` + 앞선 이유). */
        const val REPLACED_PREFIX = "REPLACED_"
    }
}
