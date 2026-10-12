package dev.sumin.skeleton.payment.testing

import dev.sumin.skeleton.crypto.TextEncryptor
import dev.sumin.skeleton.payment.CreateOrderCommand
import dev.sumin.skeleton.payment.CreatedOrder
import dev.sumin.skeleton.payment.DefaultPaymentTaskRunner
import dev.sumin.skeleton.payment.IdempotencyKey
import dev.sumin.skeleton.payment.OrderExpireTaskHandler
import dev.sumin.skeleton.payment.OrderFulfilTaskHandler
import dev.sumin.skeleton.payment.OrderReconcileTaskHandler
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentCatalog
import dev.sumin.skeleton.payment.PaymentConvergence
import dev.sumin.skeleton.payment.LedgerRefundConvergence
import dev.sumin.skeleton.payment.PaymentFulfillment
import dev.sumin.skeleton.payment.PaymentRefundApplier
import dev.sumin.skeleton.payment.PaymentAccountErasure
import dev.sumin.skeleton.payment.PaymentSubscriptions
import dev.sumin.skeleton.payment.SubscriptionRenewTaskHandler
import dev.sumin.skeleton.payment.SubscriptionRenewalGuard
import dev.sumin.skeleton.payment.PaymentTaskTypes
import dev.sumin.skeleton.payment.SubscriptionCharges
import dev.sumin.skeleton.payment.SubscriptionLifecycle
import dev.sumin.skeleton.payment.SubscriptionPlan
import dev.sumin.skeleton.payment.SubscriptionPlans
import dev.sumin.skeleton.payment.SubscriptionPlanChanges
import dev.sumin.skeleton.payment.SubscriptionRevokeTaskHandler
import dev.sumin.skeleton.payment.KoreaConsumerCancelPolicy
import dev.sumin.skeleton.payment.SubscriptionCancelPolicies
import dev.sumin.skeleton.payment.SubscriptionCancelRefundTaskHandler
import dev.sumin.skeleton.payment.SubscriptionCancellations
import dev.sumin.skeleton.payment.SubscriptionUsageCheck
import dev.sumin.skeleton.payment.PaymentWebhookInbox
import dev.sumin.skeleton.payment.PaymentWebhookTaskHandler
import dev.sumin.skeleton.payment.WebhookDelivery
import dev.sumin.skeleton.payment.WebhookReceipt
import dev.sumin.skeleton.payment.PaymentRefunds
import dev.sumin.skeleton.payment.RefundApplyTaskHandler
import dev.sumin.skeleton.payment.RefundReconcileTaskHandler
import dev.sumin.skeleton.payment.PaymentItemNotPurchasableException
import dev.sumin.skeleton.payment.PaymentLedgerWriter
import dev.sumin.skeleton.payment.PaymentMode
import dev.sumin.skeleton.payment.PaymentOrder
import dev.sumin.skeleton.payment.PaymentOrderListener
import dev.sumin.skeleton.payment.PaymentOrders
import dev.sumin.skeleton.payment.PaymentProperties
import dev.sumin.skeleton.payment.PaymentProviderRouter
import dev.sumin.skeleton.payment.PaymentRefund
import dev.sumin.skeleton.payment.PaymentReviewNotifier
import dev.sumin.skeleton.payment.PaymentReviewReason
import dev.sumin.skeleton.payment.PurchaseQuote
import dev.sumin.skeleton.payment.PurchaseRequest
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** 서버가 가진 금액 목록(1,000 · 3,000 · 5,000원) — 샘플 앱의 응원하기와 같은 모양. 목록에 없는 상품 · 수량은 살 수 없다. */
class CheerCatalog : PaymentCatalog {
    private val amounts = mapOf("cheer-1000" to 1000L, "cheer-3000" to 3000L, "cheer-5000" to 5000L)
    val quotes = CopyOnWriteArrayList<PurchaseRequest>()

    override fun quote(request: PurchaseRequest): PurchaseQuote {
        quotes += request
        val amount = amounts[request.itemRef]
        if (request.itemType != "cheer" || amount == null || request.quantity != 1) throw PaymentItemNotPurchasableException()
        return PurchaseQuote(PaymentAmount(amount, "KRW"), "Cheer ${request.itemRef.substringAfter('-')}")
    }
}

/** 지급 · 회수 리스너 대역 — [failPaid] 만큼 `onPaid` 가 던진다. */
class RecordingOrderListener : PaymentOrderListener {
    val paid = CopyOnWriteArrayList<PaymentOrder>()
    val refunded = CopyOnWriteArrayList<Pair<PaymentOrder, PaymentRefund>>()

    /** 성공한 호출을 불린 순서대로 — `paid` / `refunded`. 두 훅의 순서를 단정하는 시험용. */
    val calls = CopyOnWriteArrayList<String>()
    private val failures = AtomicInteger()
    private val refundFailures = AtomicInteger()
    val attempts = AtomicInteger()

    fun failPaid(times: Int) = failures.set(times)

    /** [times] 번 `onRefunded` 가 던진다(회수가 안 된 채 남는 줄을 만든다). */
    fun failRefunded(times: Int) = refundFailures.set(times)

    override fun onPaid(order: PaymentOrder) {
        attempts.incrementAndGet()
        if (failures.getAndUpdate { if (it > 0) it - 1 else it } > 0) error("planted listener failure")
        paid += order
        calls += "paid"
    }

    override fun onRefunded(order: PaymentOrder, refund: PaymentRefund) {
        if (refundFailures.getAndUpdate { if (it > 0) it - 1 else it } > 0) error("planted listener failure")
        refunded += order to refund
        calls += "refunded"
    }
}

/** 시험용 구독 플랜 — 3,900원(`basic`) · 6,900원(`plus`) 월 구독. 시험이 목록을 바꾼다. */
class TestSubscriptionPlans : SubscriptionPlans {
    val plans = mutableListOf(
        SubscriptionPlan("basic", "Basic", PaymentAmount(3_900, "KRW"), "P1M", 1),
        SubscriptionPlan("plus", "Plus", PaymentAmount(6_900, "KRW"), "P1M", 2),
    )

    override fun list(): List<SubscriptionPlan> = plans.toList()
}

/** 시험용 암호기 — 되돌릴 수 있지만 평문이 그대로 보이지는 않는다(저장된 값이 평문이 아님을 가르려는 것). */
object ReversibleTestEncryptor : TextEncryptor {
    override fun encrypt(plainText: String): String = "enc:" + plainText.reversed()

    override fun decrypt(cipherText: String): String = cipherText.removePrefix("enc:").reversed()
}

class RecordingReviewNotifier : PaymentReviewNotifier {
    val flagged = CopyOnWriteArrayList<Pair<String, PaymentReviewReason>>()

    override fun flagged(orderId: String, reason: PaymentReviewReason) {
        flagged += orderId to reason
    }
}

/**
 * 장부 서비스 한 벌을 메모리 대역으로 조립한다 — B3 이후의 단계(환불 · 웹훅 · 샘플 앱 시험)가 같은 조립을 다시 쓴다.
 * 새 서비스가 생기면 여기에 더한다(운영 조립은 `PaymentLedgerAutoConfiguration` 이고 이 조립은 그것의 시험용 거울이다).
 */
class PaymentLedgerKit(
    val properties: PaymentProperties = PaymentProperties(webhook = PaymentProperties.Webhook(pathToken = WEBHOOK_TOKEN), subscription = PaymentProperties.Subscription(enabled = true)),
    mode: PaymentMode = PaymentMode.TEST,
    val catalog: PaymentCatalog? = CheerCatalog(),
    val listener: RecordingOrderListener = RecordingOrderListener(),
    val review: RecordingReviewNotifier = RecordingReviewNotifier(),
    /** 「썼는가」 앱 계약의 대역 - 기본은 안 쓴 것. */
    val usage: SubscriptionUsageCheck = SubscriptionUsageCheck.NEVER_USED,
    /** 시험 조립의 해지 정책 지도 - `KR` 은 한국 정책(위약금 10%), 그 밖은 기간 끝 해지만. */
    val cancelPolicies: SubscriptionCancelPolicies = SubscriptionCancelPolicies(listOf(KoreaConsumerCancelPolicy(penaltyPercent = 10, withdrawalDays = 7)), mapOf("KR" to KoreaConsumerCancelPolicy.NAME)),
) {
    val time = MutableTime()
    val store = InMemoryPaymentLedgerStore()
    val gateway = ScriptedGateway(mode = mode, now = { time.now() }, openTransaction = store::hasOpenTransaction)
    val tasks = InMemoryPaymentTasks(store)
    val router = PaymentProviderRouter(listOf(gateway), properties)
    val writer = PaymentLedgerWriter(time)
    val lifecycle = SubscriptionLifecycle(tasks, writer, properties, review)
    val convergence = PaymentConvergence(writer, tasks, review, LedgerRefundConvergence(writer, tasks, review)).also { it.subscriptions = lifecycle }
    val plans = TestSubscriptionPlans()
    val fulfillment = PaymentFulfillment(store, listener, writer, lifecycle)
    val orders = PaymentOrders(store, tasks, router, catalog, convergence, fulfillment, writer, properties)
    val expire = OrderExpireTaskHandler(store, router, convergence)
    val reconcile = OrderReconcileTaskHandler(store, router, convergence).also { it.subscriptions = lifecycle }
    val fulfil = OrderFulfilTaskHandler(fulfillment, store, convergence)
    val applier = PaymentRefundApplier(store, listener, fulfillment, writer, lifecycle)
    val refunds = PaymentRefunds(store, tasks, router, convergence, applier, writer, properties)
    val refundReconcile = RefundReconcileTaskHandler(store, router, convergence, writer, properties)
    val refundApply = RefundApplyTaskHandler(applier, store, convergence)
    val inbox = PaymentWebhookInbox(store, tasks, router, writer, properties)
    val webhook = PaymentWebhookTaskHandler(store, router, convergence, writer)
    val charges = SubscriptionCharges(store, convergence, fulfillment, writer)
    val subscriptions = PaymentSubscriptions(store, router, plans, ReversibleTestEncryptor, lifecycle, charges, tasks, writer, properties)
    val planChanges = SubscriptionPlanChanges(store, router, plans, ReversibleTestEncryptor, lifecycle, charges, writer, properties)
    val revoke = SubscriptionRevokeTaskHandler(store, router, ReversibleTestEncryptor, writer)
    val cancellations = SubscriptionCancellations(store, plans, cancelPolicies, usage, lifecycle, refunds, tasks, writer, properties)
    val cancelRefund = SubscriptionCancelRefundTaskHandler(store, cancellations)

    /** 앱의 갱신 판단 대역 — [blocked] 에 든 계정은 갱신하지 않는다. */
    val blocked: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    val renew = SubscriptionRenewTaskHandler(store, router, plans, ReversibleTestEncryptor, lifecycle, charges, SubscriptionRenewalGuard { it !in blocked }, writer, properties.subscription.renewal.retryInterval)
    val erasure = PaymentAccountErasure(store, writer, properties.erasure.accountId, lifecycle)
    val runner = DefaultPaymentTaskRunner(listOf(expire, reconcile, fulfil, refundReconcile, refundApply, webhook, revoke, renew, cancelRefund))

    /** 지금 시각까지 시간이 된 작업을 한 번씩 돈다. */
    fun runTasks(only: Set<String>? = null): Int = tasks.runDue(runner, time.now(), only)

    fun advance(by: Duration) = time.advance(by)

    /** 웹훅이 주소(결제사 id · 비밀 조각)를 통과해 수신함에 닿는다 — 컨트롤러가 하는 두 걸음([PaymentWebhookInbox.admit] → [PaymentWebhookInbox.receive]). */
    fun deliver(delivery: WebhookDelivery, token: String? = WEBHOOK_TOKEN, provider: String = gateway.providerId): WebhookReceipt =
        inbox.receive(inbox.admit(provider, token), delivery)

    /** 수신함 처리 작업만 돈다. */
    fun runWebhooks(): Int = runTasks(setOf(PaymentTaskTypes.WEBHOOK))

    fun create(
        accountId: String = "acc_1",
        itemRef: String = "cheer-3000",
        key: String = "key-1",
        quantity: Int = 1,
    ): CreatedOrder = orders.create(CreateOrderCommand(accountId, "cheer", itemRef, IdempotencyKey(key), quantity))

    /** 주문을 만들고 브라우저가 결제창에서 인증까지 마친 상태로 만든다. */
    fun createAuthenticated(
        accountId: String = "acc_1",
        itemRef: String = "cheer-3000",
        key: String = "key-1",
        paymentKey: String = "pay_1",
    ): PaymentOrder {
        val created = create(accountId, itemRef, key).order
        gateway.authenticate(paymentKey, created.id, created.amount)
        return created
    }

    /** 결제까지 끝난 주문 하나(결제 키 `pay_1`, 3,000원). */
    fun paid(accountId: String = "acc_1", key: String = "key-1", paymentKey: String = "pay_1"): PaymentOrder {
        val created = createAuthenticated(accountId, "cheer-3000", key, paymentKey)
        return orders.confirm(accountId, created.id, paymentKey, created.amount)
    }

    fun order(id: String): PaymentOrder = checkNotNull(store.findOrder(id)) { "no such order in the memory ledger" }

    companion object {
        /** 서명 없는 가짜 결제사의 웹훅 주소 조각 — 길이 · 글자가 가드의 약한 비밀 기준을 넘는다. */
        const val WEBHOOK_TOKEN = "kit-webhook-token-0123456789abcdef"
    }
}
