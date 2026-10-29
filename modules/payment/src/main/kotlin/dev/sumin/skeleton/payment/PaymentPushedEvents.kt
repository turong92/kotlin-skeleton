package dev.sumin.skeleton.payment

import org.slf4j.LoggerFactory

/**
 * 서명된 웹훅이 곧 사실인 결제사([PaymentPushGateway])의 사건을 장부에 옮긴다 - 결제(단건 · 구독)와 구독 상태. **조회하지 않는다**: 서명이 진위를, 결제사가 말한 금액 · 통화가 사실을 보증한다.
 *
 * **무엇을 샀는지는 서버가 정했다**: 결제사의 거래는 서버가 먼저 만들었고(가격 · 수량 · 우리 줄 id 는 서버가 정한 값, [PaymentTransactionGateway]) 거래 id 가 우리 줄에 저장돼 있다.
 * 웹훅은 그 **거래 id 로만** 줄을 찾는다 - 단건은 주문의 결제 키, 구독의 첫 결제는 구독의 첫 거래 id. 구독의 갱신 · 상태 사건은 **줄에 연결된 결제사 구독 id** 로만 찾는다
 * (첫 결제가 그 id 를 연결한다). `custom_data` 는 찾는 데 쓰지 않고 교차 확인에만 쓴다. 우리 거래도 우리 구독도 아닌 사건은 반영하지 않는다.
 *
 * 모든 걸음은 호출하는 처리 작업의 한 트랜잭션 안이고 몇 번을 불러도 같은 결과다(같은 거래는 한 번, 낡은 구독 사건은 무시).
 * 돌려주는 값: null = 반영했다(또는 이미 반영돼 있다), 문자열 = 반영하지 않은 이유(수신함 줄이 `IGNORED` 로 닫히며 `last_error` 에 이 코드가 적힌다).
 * 락: 단건은 주문 줄 하나, 구독은 구독 줄 하나만 잠그고 구독 결제의 주문은 **넣기만** 한다(주문 → 구독 순서와 부딪히지 않는다).
 *
 * @param subscriptions 구독을 켠 조립에서만 - 없으면 구독 사건은 반영하지 않는다.
 */
class PaymentPushedEvents(
    private val store: PaymentLedgerStore,
    private val tasks: PaymentTasks,
    private val writer: PaymentLedgerWriter,
    private val subscriptions: SubscriptionLifecycle? = null,
    private val review: PaymentReviewNotifier = PaymentReviewNotifier.NONE,
    private val chargebacks: PaymentChargebackAlert = PaymentChargebackAlert.NONE,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val flags = PaymentReviewFlags(writer, review)
    private val refundLines = RefundLines(writer, tasks)

    fun apply(tx: PaymentLedgerTx, gateway: PaymentPushGateway, event: PushedPaymentEvent): String? =
        when (event) {
            is PushedPayment -> payment(tx, gateway, event)
            is PushedSubscription -> subscriptionState(tx, gateway, event)
            is PushedPaymentFailure -> paymentFailure(tx, gateway, event)
            is PushedRefund -> refund(tx, gateway, event)
        }

    /**
     * 구독을 켜는 길이 계정의 자리(`active_key`) 충돌로 막혔을 때 - 되돌려진 트랜잭션 뒤에 다시 부른다. 결제는 「확인 필요」로 기록하고 결제사 구독은 해지한다.
     */
    fun applyConflicted(tx: PaymentLedgerTx, gateway: PaymentPushGateway, event: PushedPaymentEvent): String {
        val provider = gateway.providerId.trim().lowercase()
        val lifecycle = subscriptions
        if (event is PushedPayment) {
            val found = store.findSubscriptionByFirstTransaction(provider, event.providerPaymentId)?.id?.let(tx::lockSubscription)
            if (found != null && lifecycle != null) unapplied(tx, lifecycle, found, event, null, cancel = false)
            cancelAtProvider(provider, event.providerSubscriptionId)
        }
        return SEAT_TAKEN
    }

    private fun payment(tx: PaymentLedgerTx, gateway: PaymentPushGateway, event: PushedPayment): String? {
        val provider = gateway.providerId.trim().lowercase()
        // 단건: 주문에 저장된 결제 키(= 서버가 만든 거래 id)로 찾는다
        val order = tx.findOrderByProviderPayment(provider, event.providerPaymentId)
        if (order != null) return if (order.kind == PaymentOrderKind.ONE_TIME) oneTime(tx, gateway, event, order.id) else null // 구독 결제는 이미 반영됐다(같은 거래)
        return subscriptionPayment(tx, gateway, event)
    }

    /** 결제 시도 실패 - 주문이면 그 주문의 이력에 사유 코드 한 줄(상태는 그대로 PENDING). 구독의 첫 결제는 이력을 붙일 주문 줄이 없어 로그만, 갱신 실패는 구독 사건(past_due)이 다룬다. */
    private fun paymentFailure(tx: PaymentLedgerTx, gateway: PaymentPushGateway, event: PushedPaymentFailure): String? {
        val provider = gateway.providerId.trim().lowercase()
        val order = tx.findOrderByProviderPayment(provider, event.providerPaymentId)
        if (order == null) {
            if (store.findSubscriptionByFirstTransaction(provider, event.providerPaymentId) == null) return UNKNOWN_TRANSACTION
            log.warn("the first payment of a subscription failed provider={} reason={}", provider, LedgerValues.code(event.errorCode) ?: "-")
            return null
        }
        if (order.status.isPaidFamily) return null
        tx.appendEvent(writer.event(order.id, PaymentEventType.PAYMENT_FAILED, PaymentActor.PROVIDER, providerCode = LedgerValues.code(event.errorCode), from = order.status, to = order.status))
        return null
    }

    private fun oneTime(tx: PaymentLedgerTx, gateway: PaymentPushGateway, event: PushedPayment, orderId: String): String? {
        val order = tx.lockOrder(orderId) ?: return UNKNOWN_ORDER
        if (order.status.isPaidFamily) return null
        // 우리 주문을 찾은 뒤의 거절은 수신함만 닫지 않는다 - 돈이 들어왔을 수 있으니 주문에 「확인 필요」를 찍는다(지급 없음)
        val context = ConvergeContext(gateway.mode, ConvergeSource.WEBHOOK, PaymentActor.PROVIDER)
        if (order.providerMode != gateway.mode) {
            flags.flag(tx, order, PaymentReviewReason.MODE_MISMATCH, context)
            return MODE_MISMATCH
        }
        // 교차 확인 - 서버가 거래에 넣은 우리 주문 id 와 다르면 반영하지 않는다
        if (event.ourOrderId != null && event.ourOrderId != order.id) {
            flags.flag(tx, order, PaymentReviewReason.ORDER_MISMATCH, context)
            return ORDER_MISMATCH
        }
        // 서버가 거래에 넣은 품목과 같은가 - 결제창에서 품목을 바꿔 결제했다면 지급하지 않는다
        if (!boughtExactly(event.items, gateway.priceIdOf(order.itemRef), order.quantity)) {
            flags.flag(tx, order, PaymentReviewReason.AMOUNT_MISMATCH, context)
            return PRICE_MISMATCH
        }
        warnOnDrift(order.id, order.itemRef, order.amount, event)
        // 금액 · 통화는 결제사가 받은 그대로다(구매자가 낸 통화)
        val paid = writer.transition(tx, order, PaymentOrderStatus.PAID, PaymentEventType.CONVERGED, PaymentActor.PROVIDER, providerCode = WEBHOOK) {
            it.copy(
                amount = event.total,
                paidAmount = event.total.amount,
                taxAmount = event.tax,
                paidAt = event.paidAt,
                method = LedgerValues.method(event.method),
                cardIssuer = if (it.accountErasedAt == null) LedgerValues.cardIssuer(event.card?.issuer) else null,
                cardLast4 = if (it.accountErasedAt == null) LedgerValues.cardLast4(event.card?.last4) else null,
                failureCode = null,
                closedAt = null,
                reviewReason = null,
            )
        }
        tasks.enqueue(PaymentTask(PaymentTaskTypes.ORDER_FULFIL, order.id))
        if (event.buyerCountry == KOREA) flags.flag(tx, paid, PaymentReviewReason.BUYER_COUNTRY_KR, context)
        return null
    }

    /**
     * 결제사가 확정한 금액(세전)이 우리 DB 가격과 다르면 경고 한 줄 - **같은 통화일 때만**(구매자 통화로 환산됐으면 비교할 수 없다). 막지 않는다: 기록은 결제사 금액 그대로, 지급도 그대로.
     * 결제사 쪽 가격을 운영자가 따로 관리하는 한 어긋날 수 있으니(링크가 가리키는 가격이 바뀌었다 등) 사람이 알아채게만 한다.
     */
    private fun warnOnDrift(orderId: String, item: String, expected: PaymentAmount, event: PushedPayment) {
        if (expected.normalizedCurrency != event.total.normalizedCurrency) return
        val paid = event.total.amount - (event.tax ?: 0)
        if (paid != expected.amount) log.warn("payment.price-drift order={} item={} expected={} paid={}", orderId, item, expected.amount, paid)
    }

    private fun boughtExactly(items: List<PushedItem>, expectedPrice: String?, quantity: Int): Boolean =
        expectedPrice != null && items.size == 1 && items[0].priceId == expectedPrice && items[0].quantity == quantity

    private fun subscriptionPayment(tx: PaymentLedgerTx, gateway: PaymentPushGateway, event: PushedPayment): String? {
        val provider = gateway.providerId.trim().lowercase()
        val lifecycle = subscriptions ?: return SUBSCRIPTIONS_OFF
        // 첫 결제: 서버가 미리 만든 거래 id 가 걸린 줄. 갱신: 줄에 연결된 결제사 구독 id 의 결제(그 구독의 거래만 - 단건 거래는 구독 id 가 없다)
        val byFirst = store.findSubscriptionByFirstTransaction(provider, event.providerPaymentId)
        val id = byFirst?.id ?: event.providerSubscriptionId?.let { store.findSubscriptionByProviderId(provider, it)?.id } ?: return UNKNOWN_TRANSACTION
        val subscription = tx.lockSubscription(id)?.takeIf { it.provider == provider } ?: return UNKNOWN_TRANSACTION
        // 우리 줄을 찾은 뒤의 거절은 결제를 기록하고 「확인 필요」로 표시한다(수신함만 닫으면 돈이 들어온 사실이 운영자에게 안 보인다). 자동 해지는 붙이지 않는다 - 운영자가 본다
        if (subscription.providerMode != gateway.mode) return unapplied(tx, lifecycle, subscription, event, MODE_MISMATCH, cancel = false)
        if (event.ourSubscriptionId != null && event.ourSubscriptionId != subscription.id) return unapplied(tx, lifecycle, subscription, event, SUBSCRIPTION_MISMATCH, cancel = false) // 교차 확인
        val first = subscription.status == SubscriptionStatus.PENDING // 아직 켜지지 않은 줄의 첫 결제(첫 거래 id 로 왔든, 연결된 구독의 거래로 왔든)
        return when {
            // 끝난 구독에 결제가 왔다 - 탈퇴로 끝났다면 결제사는 아직 과금 중이다
            subscription.status == SubscriptionStatus.ENDED ->
                unapplied(tx, lifecycle, subscription, event, ALREADY_ENDED, cancel = subscription.endReason == SubscriptionEndReason.ACCOUNT_ERASED)
            byFirst != null && !first -> null // 첫 거래가 이미 반영된 줄 - 같은 거래의 재전달
            // 구독을 켜려면 결제사 구독 id 가 필요하다 - 이 결제에도, `subscription.created` 의 거래 id 로 이미 연결된 줄에도 없으면 연결할 수 없다
            first && subscription.providerSubscriptionId == null && event.providerSubscriptionId == null ->
                unapplied(tx, lifecycle, subscription, event, NO_PROVIDER_SUBSCRIPTION, cancel = false)
            // 첫 결제만 품목을 대조한다(갱신은 가격 인상 등으로 설정과 다를 수 있다) - 서버가 거래에 넣은 품목이 아니면 켜지 않는다
            first && !boughtExactly(event.items, gateway.priceIdOf(subscription.planCode), 1) ->
                unapplied(tx, lifecycle, subscription, event, PRICE_MISMATCH, cancel = false)
            event.subscriptionChange -> applyChange(tx, gateway, lifecycle, subscription, event)
            else -> applyPayment(tx, lifecycle, subscription, event)
        }
    }

    /** 플랜 변경으로 생긴 거래 - 주문 한 줄만 남긴다. 구독의 금액 · 주기 · 기간 · 상태는 갱신과 구독 사건이 정하고 이 거래는 정하지 않는다(일할 결제액이 정기 금액을 덮으면 안 된다). */
    private fun applyChange(tx: PaymentLedgerTx, gateway: PaymentPushGateway, lifecycle: SubscriptionLifecycle, subscription: PaymentSubscription, event: PushedPayment): String? {
        // 거래 품목은 새 품목(+1)과 옛 품목(수량 -1) 두 줄일 수 있다 - 수량이 양수이고 플랜 표가 아는 품목이 바뀐 플랜이다
        val planCode = event.items.filter { it.quantity > 0 }.firstNotNullOfOrNull { gateway.itemRefOf(it.priceId)?.takeIf { code -> lifecycle.plans()?.find(code) != null } } ?: subscription.planCode
        val order = paidOrder(lifecycle, subscription, event).copy(kind = PaymentOrderKind.SUBSCRIPTION_CHANGE, itemRef = planCode)
        if (!tx.insertOrderIfAbsent(order)) return null
        tx.appendEvent(writer.event(order.id, PaymentEventType.CONVERGED, PaymentActor.PROVIDER, providerCode = WEBHOOK, to = PaymentOrderStatus.PAID))
        tasks.enqueue(PaymentTask(PaymentTaskTypes.ORDER_FULFIL, order.id))
        return null
    }

    private fun applyPayment(tx: PaymentLedgerTx, lifecycle: SubscriptionLifecycle, subscription: PaymentSubscription, event: PushedPayment): String? {
        val koreanBuyer = event.buyerCountry == KOREA && subscription.status == SubscriptionStatus.PENDING // 첫 결제에서 한 번만 - 갱신마다 찍지 않는다
        val order = paidOrder(lifecycle, subscription, event).let { if (koreanBuyer) it.copy(reviewReason = PaymentReviewReason.BUYER_COUNTRY_KR) else it }
        if (!tx.insertOrderIfAbsent(order)) return null
        if (subscription.status == SubscriptionStatus.PENDING) warnOnDrift(order.id, subscription.planCode, subscription.amount, event) // 첫 결제만 - 갱신은 결제사 쪽 가격이 따로 움직일 수 있다
        tx.appendEvent(writer.event(order.id, PaymentEventType.CONVERGED, PaymentActor.PROVIDER, providerCode = WEBHOOK, to = PaymentOrderStatus.PAID))
        if (koreanBuyer) {
            tx.appendEvent(writer.event(order.id, PaymentEventType.REVIEW_FLAGGED, PaymentActor.PROVIDER, providerCode = PaymentReviewReason.BUYER_COUNTRY_KR.name, from = order.status, to = order.status))
            review.flagged(order.id, PaymentReviewReason.BUYER_COUNTRY_KR)
        }
        tasks.enqueue(PaymentTask(PaymentTaskTypes.ORDER_FULFIL, order.id))

        val base = subscription.copy(
            providerSubscriptionId = subscription.providerSubscriptionId ?: event.providerSubscriptionId,
            amount = event.total,
            period = event.period ?: subscription.period,
            chargeOrderId = order.id,
        )
        when {
            subscription.status == SubscriptionStatus.PENDING -> activate(tx, lifecycle, base, event.periodStart ?: event.paidAt, event.periodEnd)
            // 갱신이 끝내 결제돼 연체가 풀린다(이 구독의 거래만 여기에 온다) - 더 새로운 구독 사건이 이미 상태를 정했다면 따르지 않는다
            subscription.status == SubscriptionStatus.PAST_DUE && !event.occurredAt.isBefore(subscription.providerSyncedAt ?: event.occurredAt) ->
                lifecycle.save(tx, base, SubscriptionStatus.ACTIVE) { it.copy(attempt = 1) }
            else -> tx.updateSubscription(base.copy(updatedAt = writer.now()))
        }
        return null
    }

    private fun paidOrder(lifecycle: SubscriptionLifecycle, subscription: PaymentSubscription, event: PushedPayment): PaymentOrder {
        val kind = if (subscription.chargeOrderId == null) PaymentOrderKind.SUBSCRIPTION_INITIAL else PaymentOrderKind.SUBSCRIPTION_RENEWAL
        // 크레딧으로 낸 몫은 돈이 오가지 않았다 - 실제로 낸 돈이 주문 금액이고, 정가와 크레딧은 할인 칸에 남는다(토스의 주기 늘리기 공제와 같은 표현)
        // 바깥에서 낸 내리기 변경 거래의 total 은 음수일 수 있다(남은 몫이 고객 잔액으로 간다) - 낸 돈은 0 이다
        val gross = event.total.amount.coerceAtLeast(0)
        val credit = event.credit?.coerceIn(0, gross)?.takeIf { it > 0 }
        val paid = event.total.copy(amount = gross - (credit ?: 0))
        return lifecycle.chargeOrder(
            subscription, kind, subscription.planCode, amount = paid, dedupeKey = "${subscription.provider}:${event.providerPaymentId}",
            originalAmount = credit?.let { gross },
        ).copy(
            status = PaymentOrderStatus.PAID,
            providerPaymentId = event.providerPaymentId,
            paidAmount = paid.amount,
            taxAmount = event.tax,
            method = LedgerValues.method(event.method),
            cardIssuer = LedgerValues.cardIssuer(event.card?.issuer),
            cardLast4 = LedgerValues.cardLast4(event.card?.last4),
            confirmStartedAt = null,
            checkoutExpiresAt = event.paidAt,
            paidAt = event.paidAt,
        )
    }

    /**
     * 돈은 받았지만 이 구독이 받아들일 결제가 아니다 - 주문으로 기록하되 **구독은 건드리지 않고 지급 작업도 넣지 않으며** `SUBSCRIPTION_STALE_CHARGE`(확인 필요, 운영자가 환불)로 표시한다.
     * [cancel] 이면 그 결제사 구독을 즉시 해지하는 작업을 넣는다(계속 과금되지 않게). 돌려주는 값은 수신함 줄의 사유.
     */
    private fun unapplied(tx: PaymentLedgerTx, lifecycle: SubscriptionLifecycle, subscription: PaymentSubscription, event: PushedPayment, code: String?, cancel: Boolean): String {
        val order = paidOrder(lifecycle, subscription, event).copy(reviewReason = PaymentReviewReason.SUBSCRIPTION_STALE_CHARGE)
        if (tx.insertOrderIfAbsent(order)) {
            tx.appendEvent(writer.event(order.id, PaymentEventType.CONVERGED, PaymentActor.PROVIDER, providerCode = WEBHOOK, to = PaymentOrderStatus.PAID))
            tx.appendEvent(writer.event(order.id, PaymentEventType.REVIEW_FLAGGED, PaymentActor.PROVIDER, providerCode = PaymentReviewReason.SUBSCRIPTION_STALE_CHARGE.name, from = order.status, to = order.status))
            review.flagged(order.id, PaymentReviewReason.SUBSCRIPTION_STALE_CHARGE)
            log.warn("a provider payment was recorded but not applied order={} reason={}", order.id, code)
        }
        if (cancel) cancelAtProvider(subscription.provider, event.providerSubscriptionId)
        return code ?: SEAT_TAKEN
    }

    private fun cancelAtProvider(provider: String, providerSubscriptionId: String?) {
        if (providerSubscriptionId != null) tasks.enqueue(PaymentTask(PaymentTaskTypes.PROVIDER_SUBSCRIPTION_CANCEL, "${provider.trim().lowercase()}:$providerSubscriptionId"))
    }

    /**
     * 결제사가 환불에 내린 결정. 서명된 본문이 사실이라 조회하지 않는다. 줄은 **번호**(접수 때 박은 것)로 찾고, 번호가 없으면 같은 금액의 열린 `PENDING` 줄 하나에 붙인다
     * (응답을 잃은 호출 - 결제사는 거래당 검토 중 환불이 하나뿐이고 장부는 주문당 열린 환불이 하나뿐이라 짝이 하나다). 우리 줄이 없는 승인은 결제사 관리 화면의 환불이라 `PROVIDER` 줄로 넣는다.
     * **미결제 주문이면 던져 재시도한다**(결제 완료 웹훅이 아직 반영되지 않았을 수 있다 - 품목 · 주문 불일치로 거절된 주문만 무시).
     */
    private fun refund(tx: PaymentLedgerTx, gateway: PaymentPushGateway, event: PushedRefund): String? {
        val provider = gateway.providerId.trim().lowercase()
        val known = tx.findOrderByProviderPayment(provider, event.providerPaymentId)
            ?: run {
                // 구독의 첫 거래 · 우리 구독에 연결된 결제사 구독 id(Paddle adjustment 의 `subscription_id`)의 갱신 거래는 우리 것이다 - 결제 완료 웹훅이 아직 반영되지 않았을 뿐이니 던져서 다시 한다. 그 밖은 무시한다
                val ours = store.findSubscriptionByFirstTransaction(provider, event.providerPaymentId) != null ||
                    event.providerSubscriptionId?.let { store.findSubscriptionByProviderId(provider, it) } != null
                check(!ours) { "the payment of webhook event ${event.eventId} is not recorded yet" }
                return UNKNOWN_TRANSACTION
            }
        val order = tx.lockOrder(known.id) ?: return UNKNOWN_ORDER
        val context = ConvergeContext(gateway.mode, ConvergeSource.WEBHOOK, PaymentActor.PROVIDER)
        if (order.providerMode != gateway.mode) {
            flags.flag(tx, order, PaymentReviewReason.MODE_MISMATCH, context)
            return MODE_MISMATCH
        }
        // 우리 주문인데 결제 완료 웹훅이 아직 반영되지 않았다 - 던져서 처리 작업이 다시 하게 한다(되돌림이 차지백보다 먼저 온 경우와 같다)
        if (!order.status.isPaidFamily) {
            // 결제 웹훅이 품목 · 주문 불일치로 거절된 주문은 결제 완료가 끝내 오지 않는다 - 재시도해도 소용없으니 예전처럼 무시한다
            if (order.reviewReason == PaymentReviewReason.AMOUNT_MISMATCH || order.reviewReason == PaymentReviewReason.ORDER_MISMATCH) return ORDER_NOT_PAID
            error("the order of webhook event ${event.eventId} is not paid yet")
        }
        val number = LedgerValues.code(event.providerRefundId) ?: return UNREADABLE_REFUND
        val lines = tx.listRefunds(order.id)
        if (event.kind != PushedRefundKind.REFUND) return chargeback(tx, order, lines, number, event, context)
        val line = lines.firstOrNull { it.providerRefundId == number }
            ?: lines.firstOrNull { it.status == PaymentRefundStatus.PENDING && it.providerRefundId == null && it.amount.sameAs(event.amount) }
        when (event.status) {
            PushedRefundStatus.PENDING_APPROVAL ->
                if (line != null && line.status == PaymentRefundStatus.PENDING && line.providerRefundId == null) {
                    writer.transitionRefund(tx, line, PaymentRefundStatus.PENDING, PaymentEventType.REFUND_UNKNOWN, PaymentActor.PROVIDER, null, RefundSettlement.PROVIDER_PENDING) {
                        it.copy(providerRefundId = number, failureCode = it.failureCode ?: RefundSettlement.PROVIDER_PENDING)
                    }
                }
            PushedRefundStatus.APPROVED -> {
                if (line != null && line.status == PaymentRefundStatus.FAILED && line.providerRefundId == number && line.amount.sameAs(event.amount) && refundLines.fits(order, line.amount)) {
                    // 우리가 「적용 안 됨」으로 닫은 뒤에 결제사의 승인이 왔다 - 돈이 나갔다. 결제사 말대로 줄을 되살려 주문에 반영한다(같은 번호 · 같은 금액 · 합계가 넘치지 않을 때만)
                    refundLines.revive(tx, order, line, event.occurredAt, PaymentActor.PROVIDER, null)
                    return null
                }
                if (line != null && line.status == PaymentRefundStatus.FAILED) {
                    // 되살릴 수 없다(금액이 다르다 · 합계가 넘친다) - 우리가 「적용 안 됨」으로 닫은 뒤에 결제사의 승인이 왔다 - 돈이 나갔다. 닫힌 줄은 열지 않고(번호가 같아 새 줄도 못 넣는다) 사람이 보게 표시한다(응답 경로의 `lateResult` 와 같다)
                    tx.appendEvent(writer.refundEvent(order.id, line.id, PaymentEventType.REFUND_UNKNOWN, PaymentActor.PROVIDER, null, RefundSettlement.LATE_RESULT, line.status, line.status))
                    tx.updateRefund(line.copy(failureCode = RefundSettlement.LATE_RESULT, updatedAt = writer.now()))
                    flags.flag(tx, order, PaymentReviewReason.REFUND_MISMATCH, context)
                    return REFUND_MISMATCH
                }
                if (line != null && line.status != PaymentRefundStatus.PENDING) return null // 이뤄진 · 되돌려진 줄은 다시 움직이지 않는다
                val amount = line?.amount ?: event.amount
                if (!refundLines.fits(order, amount) || (line != null && !line.amount.sameAs(event.amount))) {
                    flags.flag(tx, order, PaymentReviewReason.REFUND_MISMATCH, context)
                    return REFUND_MISMATCH
                }
                if (line != null) refundLines.succeed(tx, order, line, number, event.occurredAt, PaymentActor.PROVIDER, null)
                else refundLines.addFromProvider(tx, order, ProviderRefund(number, event.amount, event.occurredAt))
            }
            PushedRefundStatus.REJECTED ->
                if (line != null && line.status == PaymentRefundStatus.PENDING) {
                    writer.transitionRefund(tx, line, PaymentRefundStatus.FAILED, PaymentEventType.REFUND_FAILED, PaymentActor.PROVIDER, null, PROVIDER_REJECTED) {
                        it.copy(providerRefundId = number, failureCode = PROVIDER_REJECTED, completedAt = writer.now())
                    }
                }
            PushedRefundStatus.REVERSED -> Unit // 되돌려지는 것은 차지백뿐이다
        }
        return null
    }

    /**
     * 차지백 - 우리가 시킨 일이 아니라 결제사가 구매자의 이의로 돈을 가져간 일. `APPROVED` 는 `CHARGEBACK` 사유의 `PROVIDER` 줄로 넣는다(회수 작업 · 전액이면 구독 끝 + 결제사 해지가 환불과 같은 길로 돈다).
     * **같은 이의를 두 번 세지 않는다**: 경고(`chargeback_warning`)와 같은 금액의 차지백(또는 그 반대 순서)은 한 줄이다 - 금액이 다르면 둘 다 넣고 `REFUND_MISMATCH` 로 사람이 본다.
     * 분쟁에서 이기면 원래 번호가 `REVERSED` 로 오거나 되돌림 adjustment 가 만들어진다 - 둘 중 어느 쪽이든(둘 다 와도) 짝 줄(같은 번호, 없으면 같은 거래 · 같은 금액의 차지백 줄)이 **한 번만** `REVERSED` 가 된다.
     * **모호하면 사람이 본다**: 번호 없는 되돌림이 같은 금액의 이뤄진 줄 둘 이상에 걸리면 어느 것인지 몰라 아무것도 되돌리지 않고 `REFUND_MISMATCH` 로 표시한다.
     * 짝 줄이 아직 없으면(되돌림이 차지백보다 먼저 도착) 던져서 처리 작업이 다시 하게 한다 - 차지백이 들어온 뒤 짝지어진다.
     */
    private fun chargeback(tx: PaymentLedgerTx, order: PaymentOrder, lines: List<PaymentRefund>, number: String, event: PushedRefund, context: ConvergeContext): String? {
        val chargebackLines = lines.filter { it.reasonCode == PaymentRefundReason.CHARGEBACK.name && it.origin == PaymentRefundOrigin.PROVIDER }
        when {
            event.kind == PushedRefundKind.CHARGEBACK && event.status == PushedRefundStatus.APPROVED -> {
                if (chargebackLines.any { it.providerRefundId == number }) return null // 같은 사건의 재전송
                if (refundLines.foldChargeback(tx, order.id, number, event.amount, event.warning)) return null // 경고와 차지백 - 이미 센 이의다(맞추기와 같은 규칙)
                if (!refundLines.fits(order, event.amount)) {
                    flags.flag(tx, order, PaymentReviewReason.REFUND_MISMATCH, context)
                    return REFUND_MISMATCH
                }
                val landed = refundLines.addFromProvider(tx, order, ProviderRefund(number, event.amount, event.occurredAt, chargeback = true, warning = event.warning))
                // 경고와 금액이 다른 차지백 - 같은 이의로 볼 수 없으니 사람이 본다
                if (chargebackLines.any { it.status == PaymentRefundStatus.SUCCEEDED && !it.amount.sameAs(event.amount) && (it.failureCode == RefundLines.CHARGEBACK_WARNING) != event.warning }) {
                    flags.flag(tx, landed, PaymentReviewReason.REFUND_MISMATCH, context)
                }
                chargebacks.chargeback(order.id)
            }
            event.kind == PushedRefundKind.CHARGEBACK_REVERSAL || (event.kind == PushedRefundKind.CHARGEBACK && event.status == PushedRefundStatus.REVERSED) -> {
                // 원래 번호로 오면 번호로 짝짓는다. 되돌림 adjustment 는 번호가 달라 같은 금액의 줄로만 짝지을 수 있다 - 같은 금액이 둘 이상이면 어느 줄인지 알 수 없어 사람이 본다
                // (잘못 고르면 원래 번호의 되돌림이 다른 줄을 또 되돌려 이중 되돌림이 된다). 같은 종류(경고 ↔ 차지백)의 줄과만 짝짓는다 - 경고의 되돌림이 접힌 차지백의 줄을 되돌리면 돈이 빠져 있는데 PAID 가 된다
                val sameKind = chargebackLines.filter { (it.failureCode == RefundLines.CHARGEBACK_WARNING) == event.warning }
                val byNumber = sameKind.firstOrNull { it.providerRefundId == number && event.kind == PushedRefundKind.CHARGEBACK }
                val sameAmount = sameKind.filter { it.amount.sameAs(event.amount) }
                if (byNumber == null && sameAmount.size > 1) {
                    if (sameAmount.none { it.status == PaymentRefundStatus.SUCCEEDED }) return null // 모두 이미 되돌렸다 - 같은 사건의 재전달
                    flags.flag(tx, order, PaymentReviewReason.REFUND_MISMATCH, context)
                    return REFUND_MISMATCH
                }
                val pair = byNumber ?: sameAmount.singleOrNull()
                if (pair == null) {
                    // 짝이 없다: 경고의 되돌림이 차지백으로 접힌 줄(번호가 차지백으로 옮겨졌다)을 만났을 때만 무시한다. 그 밖은 차지백이 아직 안 왔다 - 던져서 처리 작업이 다시 하게 한다
                    if (event.warning && chargebackLines.any { it.failureCode != RefundLines.CHARGEBACK_WARNING && it.amount.sameAs(event.amount) && (it.status == PaymentRefundStatus.SUCCEEDED || it.status == PaymentRefundStatus.REVERSED) }) return null
                    error("no chargeback line to reverse yet for webhook event ${event.eventId}")
                }
                if (pair.status != PaymentRefundStatus.SUCCEEDED) return null
                refundLines.reverse(tx, order, pair)
                chargebacks.reversed(order.id)
            }
        }
        return null
    }

    private fun subscriptionState(tx: PaymentLedgerTx, gateway: PaymentPushGateway, event: PushedSubscription): String? {
        val provider = gateway.providerId.trim().lowercase()
        val lifecycle = subscriptions ?: return SUBSCRIPTIONS_OFF
        // 줄에 연결된 결제사 구독 id 로만 찾는다 - 연결 전(첫 결제 전)에 온 사건이나 우리 구독이 아닌 사건은 반영하지 않는다.
        // 예외: 구독이 만들어진 사건이 싣는 거래 id 가 우리가 만든 첫 거래 id 면 그 줄에 결제사 구독 id 만 **연결**한다(켜지는 않는다) - 첫 결제 사건을 놓쳐도 이후 갱신이 이 줄에 닿게
        val linkedId = store.findSubscriptionByProviderId(provider, event.providerSubscriptionId)?.id
        val firstTxnId = if (linkedId == null) event.transactionId?.let { store.findSubscriptionByFirstTransaction(provider, it)?.id } else null
        val id = linkedId ?: firstTxnId ?: return UNKNOWN_SUBSCRIPTION
        val subscription = tx.lockSubscription(id)?.takeIf { it.provider == provider } ?: return UNKNOWN_SUBSCRIPTION
        if (firstTxnId != null) {
            if (subscription.providerSubscriptionId != null || subscription.status != SubscriptionStatus.PENDING) return SUBSCRIPTION_MISMATCH
            tx.updateSubscription(subscription.copy(providerSubscriptionId = event.providerSubscriptionId, providerSyncedAt = event.occurredAt, updatedAt = writer.now()))
            return null
        }
        if (subscription.providerMode != gateway.mode) return MODE_MISMATCH
        if (subscription.status == SubscriptionStatus.ENDED) return ALREADY_ENDED
        val syncedAt = subscription.providerSyncedAt
        if (syncedAt != null && event.occurredAt.isBefore(syncedAt)) return STALE_EVENT // 더 새로운 사건이 먼저 도착해 반영됐다

        var current = subscription.copy(providerSyncedAt = event.occurredAt, period = event.period ?: subscription.period)
        // 첫 결제 전의 줄은 구독 사건으로 켜지 않는다(자리 `active_key` 가 없으면 제약에 걸린다) - 연결 · 주기만 남기고 첫 결제가 켠다. 결제가 끝나지 않은 채 끝난 구독(ENDED)만 아래에서 닫는다
        if (current.status == SubscriptionStatus.PENDING && event.status != PushedSubscriptionStatus.ENDED) {
            tx.updateSubscription(current.copy(updatedAt = writer.now()))
            return null
        }
        if (event.status == PushedSubscriptionStatus.ENDED) {
            val reason = when (current.status) {
                SubscriptionStatus.PENDING -> SubscriptionEndReason.FIRST_PAYMENT_DECLINED
                SubscriptionStatus.PAST_DUE -> SubscriptionEndReason.PAYMENT_FAILED
                else -> SubscriptionEndReason.USER_CANCELED
            }
            lifecycle.end(tx, current, reason)
            return null
        }
        if (event.periodStart != null && event.periodEnd != null) {
            val moved = current.currentPeriodStart?.let { event.periodStart.isAfter(it) } ?: true
            current = current.copy(currentPeriodStart = event.periodStart, currentPeriodEnd = event.periodEnd, periodSeq = if (moved) current.periodSeq + 1 else current.periodSeq)
        }
        // 결제사 쪽에서 플랜이 바뀌었다(우리 변경 · 대시보드) - 품목이 하나이고 우리 플랜 표가 아는 코드면 플랜 코드를 맞춘다. 금액은 다음 결제가 맞춘다
        val itemCode = event.items.filter { it.quantity > 0 }.singleOrNull()?.let { gateway.itemRefOf(it.priceId) }
        if (itemCode != null && itemCode != current.planCode) {
            val plan = lifecycle.plans()?.find(itemCode)
            // 사건이 새 정기 금액을 실었고 통화가 같으면 금액도 맞춘다 - 안 실었으면 다음 결제가 맞춘다
            val amount = event.amount?.takeIf { it.currency == current.amount.currency && it.amount > 0 }?.amount ?: current.amount.amount
            if (plan != null) current = lifecycle.switched(current, plan.code, amount, event.period ?: plan.period)
        }
        val target = when (event.status) {
            PushedSubscriptionStatus.ACTIVE -> SubscriptionStatus.ACTIVE
            PushedSubscriptionStatus.PAST_DUE -> SubscriptionStatus.PAST_DUE
            else -> SubscriptionStatus.CANCELING
        }
        when {
            current.status == target || SubscriptionTransitions.isAllowed(current.status, target) -> lifecycle.save(tx, current, target)
            else -> {
                // 예: 해지 예약을 되돌린 CANCELING -> ACTIVE. 전이표에 없다 - 기간 · 반영 시각만 남기고 상태는 그대로 둔다
                log.warn("a provider subscription event asks for a transition the ledger does not allow subscription={} from={} to={}", current.id, current.status, target)
                tx.updateSubscription(current.copy(updatedAt = writer.now()))
            }
        }
        return null
    }

    /** `PENDING` 을 첫 결제가 확인된 `ACTIVE` 로 올린다 — 이 계정의 자리(`active_key`)를 쥔다. 결제사의 기간을 그대로 쓰고 없으면 우리 주기로 센다. */
    private fun activate(tx: PaymentLedgerTx, lifecycle: SubscriptionLifecycle, subscription: PaymentSubscription, start: java.time.Instant, end: java.time.Instant?): PaymentSubscription =
        lifecycle.save(tx, subscription, SubscriptionStatus.ACTIVE) {
            it.copy(
                anchorAt = start,
                periodSeq = 1,
                currentPeriodStart = start,
                currentPeriodEnd = end ?: SubscriptionPeriod.parse(it.period).after(start, 1),
                activeKey = it.accountId,
                attempt = 1,
            )
        }

    companion object {
        const val WEBHOOK = "WEBHOOK"
        private const val KOREA = "KR"
        const val UNKNOWN_ORDER = "UNKNOWN_ORDER"
        const val UNKNOWN_SUBSCRIPTION = "UNKNOWN_SUBSCRIPTION"
        const val ORDER_MISMATCH = "ORDER_MISMATCH"
        const val MODE_MISMATCH = "MODE_MISMATCH"
        const val STALE_EVENT = "STALE_EVENT"
        const val ALREADY_ENDED = "ALREADY_ENDED"
        const val SUBSCRIPTIONS_OFF = "SUBSCRIPTIONS_OFF"
        const val UNKNOWN_TRANSACTION = "UNKNOWN_TRANSACTION"
        const val SUBSCRIPTION_MISMATCH = "SUBSCRIPTION_MISMATCH"
        const val NO_PROVIDER_SUBSCRIPTION = "NO_PROVIDER_SUB"
        const val PRICE_MISMATCH = "PRICE_MISMATCH"
        const val SEAT_TAKEN = "SEAT_TAKEN"
        const val NOT_HANDLED = "NOT_HANDLED"
        const val ORDER_NOT_PAID = "ORDER_NOT_PAID"
        const val UNREADABLE_REFUND = "UNREADABLE_REFUND"
        const val REFUND_MISMATCH = "REFUND_MISMATCH"

        /** 환불 줄의 `failure_code` · 이력의 `provider_code`: 결제사가 환불 요청을 거절했다 - 돈은 움직이지 않았고 주문은 다시 환불할 수 있다. */
        const val PROVIDER_REJECTED = "PROVIDER_REJECTED"
    }
}
