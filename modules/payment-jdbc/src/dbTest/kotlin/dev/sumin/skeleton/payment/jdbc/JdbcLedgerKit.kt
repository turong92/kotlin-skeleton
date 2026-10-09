package dev.sumin.skeleton.payment.jdbc

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.jobqueue.jdbc.JdbcJobQueue
import dev.sumin.skeleton.jobqueue.jdbc.JdbcJobRepository
import dev.sumin.skeleton.jobqueue.jdbc.JobQueueProperties
import dev.sumin.skeleton.payment.CreateOrderCommand
import dev.sumin.skeleton.payment.CreatedOrder
import dev.sumin.skeleton.payment.LedgerRefundConvergence
import dev.sumin.skeleton.payment.PaymentRefundApplier
import dev.sumin.skeleton.payment.PaymentRefunds
import dev.sumin.skeleton.payment.RefundApplyTaskHandler
import dev.sumin.skeleton.payment.RefundReconcileTaskHandler
import dev.sumin.skeleton.payment.IdempotencyKey
import dev.sumin.skeleton.payment.OrderExpireTaskHandler
import dev.sumin.skeleton.payment.OrderFulfilTaskHandler
import dev.sumin.skeleton.payment.OrderReconcileTaskHandler
import dev.sumin.skeleton.payment.PaymentConvergence
import dev.sumin.skeleton.payment.PaymentFulfillment
import dev.sumin.skeleton.payment.PaymentLedgerWriter
import dev.sumin.skeleton.payment.PaymentOrder
import dev.sumin.skeleton.payment.PaymentOrders
import dev.sumin.skeleton.payment.PaymentProperties
import dev.sumin.skeleton.payment.PaymentProviderRouter
import dev.sumin.skeleton.payment.PaymentWebhookInbox
import dev.sumin.skeleton.payment.PaymentWebhookTaskHandler
import dev.sumin.skeleton.payment.WebhookDelivery
import dev.sumin.skeleton.payment.WebhookReceipt
import dev.sumin.skeleton.payment.testing.CheerCatalog
import dev.sumin.skeleton.payment.testing.MutableTime
import dev.sumin.skeleton.payment.testing.RecordingOrderListener
import dev.sumin.skeleton.payment.testing.RecordingReviewNotifier
import dev.sumin.skeleton.payment.testing.ScriptedGateway
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate

/**
 * `payment` 의 장부 서비스 한 벌을 **진짜 JDBC 저장소 · 진짜 작업 큐**로 조립한다(`PaymentLedgerKit` 의 메모리판 거울) — 서비스가 진짜 락 위에서 경주해도 맞는지 본다.
 * 가짜 결제사는 `store::hasOpenTransaction` 을 걸어 두어 **트랜잭션 안에서 결제사를 부르면 던진다**(JDBC 저장소도 같은 약속을 지킨다는 증거).
 */
class JdbcLedgerKit(val properties: PaymentProperties = PaymentProperties()) {
    val time = MutableTime()
    val store = PaymentDb.newStore()
    val gateway = ScriptedGateway(now = { time.now() }, openTransaction = store::hasOpenTransaction)
    val listener = RecordingOrderListener()
    val review = RecordingReviewNotifier()
    private val repository = JdbcJobRepository(JdbcClient.create(PaymentDb.dataSource), TransactionTemplate(PaymentDb.transactionManager), DbTestDatabase.dialect)
    val tasks = JdbcPaymentTasks(JdbcJobQueue(repository, JobQueueProperties(), TimeProvider.systemUtc()), store)
    val router = PaymentProviderRouter(listOf(gateway), properties)
    val writer = PaymentLedgerWriter(time)
    val convergence = PaymentConvergence(writer, tasks, review, LedgerRefundConvergence(writer, tasks, review))
    val fulfillment = PaymentFulfillment(store, listener, writer)
    val orders = PaymentOrders(store, tasks, router, CheerCatalog(), convergence, fulfillment, writer, properties)
    val expire = OrderExpireTaskHandler(store, router, convergence)
    val reconcile = OrderReconcileTaskHandler(store, router, convergence)
    val fulfil = OrderFulfilTaskHandler(fulfillment, store, convergence)
    val applier = PaymentRefundApplier(store, listener, fulfillment, writer)
    val refunds = PaymentRefunds(store, tasks, router, convergence, applier, writer, properties)
    val refundReconcile = RefundReconcileTaskHandler(store, router, convergence, writer, properties)
    val refundApply = RefundApplyTaskHandler(applier, store, convergence)
    val inbox = PaymentWebhookInbox(store, tasks, router, writer, properties)
    val webhook = PaymentWebhookTaskHandler(store, router, convergence, writer)

    /** 웹훅 한 건이 수신함에 닿는다(주소 확인은 웹 시험의 몫이라 건너뛴다). */
    fun deliver(delivery: WebhookDelivery): WebhookReceipt = inbox.receive(gateway, delivery)

    fun create(accountId: String = "acc_1", itemRef: String = "cheer-3000", key: String = "key-1"): CreatedOrder =
        orders.create(CreateOrderCommand(accountId, "cheer", itemRef, IdempotencyKey(key), 1))

    /** 주문을 만들고 브라우저가 결제창에서 인증까지 마친 상태로 만든다. */
    fun createAuthenticated(key: String = "key-1", paymentKey: String = "pay_1"): PaymentOrder {
        val created = create(key = key).order
        gateway.authenticate(paymentKey, created.id, created.amount)
        return created
    }

    /** 결제까지 끝난 주문 하나(결제 키 `pay_1`, 3,000원). */
    fun paid(key: String = "key-1", paymentKey: String = "pay_1"): PaymentOrder {
        val created = createAuthenticated(key, paymentKey)
        return orders.confirm("acc_1", created.id, paymentKey, created.amount)
    }

    fun order(id: String): PaymentOrder = checkNotNull(store.findOrder(id))
}
