package dev.sumin.skeleton.payment.testing

import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentMode
import dev.sumin.skeleton.payment.PaymentOrder
import dev.sumin.skeleton.payment.PaymentOrderKind
import dev.sumin.skeleton.payment.PaymentOrderStatus
import dev.sumin.skeleton.payment.PaymentOriginType
import dev.sumin.skeleton.payment.PaymentRefund
import dev.sumin.skeleton.payment.PaymentRefundOrigin
import dev.sumin.skeleton.payment.PaymentRefundStatus
import dev.sumin.skeleton.payment.PaymentRefundTransitions
import dev.sumin.skeleton.payment.PaymentWebhookEvent
import dev.sumin.skeleton.payment.PaymentSubscription
import dev.sumin.skeleton.payment.PaymentWebhookStatus
import dev.sumin.skeleton.payment.SubscriptionStatus
import java.time.Instant

/** 시험용 주문 줄 만들기 — 이름 붙은 인자로 필요한 칸만 바꾼다. */
object PaymentTestOrders {
    val T0: Instant = Instant.parse("2026-10-08T00:00:00Z")

    /** 시험용 구독 줄 — 시작하기 전(`PENDING`, 자리 없음)이 기본이다. */
    fun subscription(
        id: String = "sub_" + "a".repeat(32),
        accountId: String = "acc_1",
        status: SubscriptionStatus = SubscriptionStatus.PENDING,
        activeKey: String? = null,
        chargeOrderId: String? = null,
        customerKey: String? = "cus_" + id.takeLast(32),
        billingToken: String? = null,
        now: Instant = T0,
    ): PaymentSubscription = PaymentSubscription(
        id = id,
        accountId = accountId,
        provider = "scripted",
        providerMode = PaymentMode.TEST,
        status = status,
        planCode = "basic",
        amount = PaymentAmount(3900, "KRW"),
        period = "P1M",
        anchorAt = null,
        periodSeq = 0,
        currentPeriodStart = null,
        currentPeriodEnd = null,
        chargeOrderId = chargeOrderId,
        attempt = 1,
        customerKey = customerKey,
        billingToken = billingToken,
        cardIssuer = null,
        cardLast4 = null,
        activeKey = activeKey,
        pendingPlanCode = null,
        pendingAmount = null,
        endReason = null,
        endedAt = null,
        accountErasedAt = null,
        createdAt = now,
        updatedAt = now,
    )

    fun order(
        id: String = "ord_" + "a".repeat(32),
        accountId: String = "acc_1",
        status: PaymentOrderStatus = PaymentOrderStatus.PENDING,
        provider: String = "scripted",
        mode: PaymentMode = PaymentMode.TEST,
        providerPaymentId: String? = null,
        amount: PaymentAmount = PaymentAmount(3000, "KRW"),
        dedupeKey: String? = null,
        now: Instant = T0,
    ): PaymentOrder = PaymentOrder(
        id = id,
        accountId = accountId,
        kind = PaymentOrderKind.ONE_TIME,
        status = status,
        provider = provider,
        providerMode = mode,
        providerPaymentId = providerPaymentId,
        amount = amount,
        paidAmount = null,
        taxAmount = null,
        refundedAmount = 0,
        itemType = "cheer",
        itemRef = "cheer-3000",
        quantity = 1,
        orderName = "Cheer 3,000",
        originType = PaymentOriginType.CHECKOUT,
        originRef = null,
        dedupeKey = dedupeKey,
        method = null,
        cardIssuer = null,
        cardLast4 = null,
        failureCode = null,
        reviewReason = null,
        checkoutExpiresAt = now.plusSeconds(1800),
        confirmStartedAt = null,
        paidAt = null,
        fulfilledAt = null,
        closedAt = null,
        accountErasedAt = null,
        createdAt = now,
        updatedAt = now,
    )

    /** 시험용 환불 줄 — `open_key` 는 상태가 정한다(열려 있으면 주문 id). */
    fun refund(
        id: String = "rfd_" + "a".repeat(32),
        orderId: String = "ord_" + "a".repeat(32),
        status: PaymentRefundStatus = PaymentRefundStatus.REQUESTED,
        origin: PaymentRefundOrigin = PaymentRefundOrigin.USER,
        amount: PaymentAmount = PaymentAmount(1000, "KRW"),
        reasonCode: String = "CUSTOMER_REQUEST",
        dedupeKey: String? = null,
        providerRefundId: String? = null,
        now: Instant = T0,
    ): PaymentRefund = PaymentRefund(
        id = id,
        orderId = orderId,
        status = status,
        origin = origin,
        amount = amount,
        reasonCode = reasonCode,
        openKey = PaymentRefundTransitions.openKeyFor(status, orderId),
        dedupeKey = dedupeKey,
        providerRefundId = providerRefundId,
        expectedRemaining = null,
        requestedBy = if (origin == PaymentRefundOrigin.PROVIDER) null else "acc_1",
        decidedBy = null,
        failureCode = null,
        requestedAt = now,
        sentAt = null,
        completedAt = null,
        appliedAt = null,
        createdAt = now,
        updatedAt = now,
    )

    /** 시험용 수신함 줄 — 저장소가 넣을 때 `id` 와 `deliveries` 를 정한다(여기 값은 무시된다). */
    fun webhookEvent(
        provider: String = "scripted",
        eventKey: String = "evt-1",
        eventType: String = "PAYMENT_STATUS_CHANGED",
        resourceId: String = "pay_1",
        orderId: String? = null,
        status: PaymentWebhookStatus = PaymentWebhookStatus.RECEIVED,
        payload: String? = null,
        payloadHash: String = "a".repeat(64),
        receivedAt: Instant = T0,
    ): PaymentWebhookEvent = PaymentWebhookEvent(
        id = 0,
        provider = provider,
        eventKey = eventKey,
        eventType = eventType,
        resourceType = "PAYMENT",
        resourceId = resourceId,
        orderId = orderId,
        payload = payload,
        payloadHash = payloadHash,
        status = status,
        deliveries = 0,
        lastError = null,
        receivedAt = receivedAt,
        processedAt = null,
    )
}
