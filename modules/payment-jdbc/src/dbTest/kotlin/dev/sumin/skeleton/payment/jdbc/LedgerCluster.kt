package dev.sumin.skeleton.payment.jdbc

import com.zaxxer.hikari.HikariDataSource
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.jobqueue.jdbc.JdbcJobQueue
import dev.sumin.skeleton.jobqueue.jdbc.JdbcJobRepository
import dev.sumin.skeleton.jobqueue.jdbc.JobHandler
import dev.sumin.skeleton.jobqueue.jdbc.JobQueueProperties
import dev.sumin.skeleton.jobqueue.jdbc.JobQueueWorker
import dev.sumin.skeleton.payment.CheckoutInstruction
import dev.sumin.skeleton.payment.CheckoutOrder
import dev.sumin.skeleton.payment.CreateOrderCommand
import dev.sumin.skeleton.payment.CreatedOrder
import dev.sumin.skeleton.payment.DefaultPaymentTaskRunner
import dev.sumin.skeleton.payment.IdempotencyKey
import dev.sumin.skeleton.payment.LedgerRefundConvergence
import dev.sumin.skeleton.payment.OrderExpireTaskHandler
import dev.sumin.skeleton.payment.OrderFulfilTaskHandler
import dev.sumin.skeleton.payment.OrderReconcileTaskHandler
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentCheckoutGateway
import dev.sumin.skeleton.payment.PaymentConfirmGateway
import dev.sumin.skeleton.payment.PaymentConfirmRequest
import dev.sumin.skeleton.payment.PaymentConvergence
import dev.sumin.skeleton.payment.PaymentFulfillment
import dev.sumin.skeleton.payment.PaymentLedgerWriter
import dev.sumin.skeleton.payment.PaymentLookupGateway
import dev.sumin.skeleton.payment.PaymentLookupQuery
import dev.sumin.skeleton.payment.PaymentMode
import dev.sumin.skeleton.payment.PaymentOperationResult
import dev.sumin.skeleton.payment.PaymentOrder
import dev.sumin.skeleton.payment.PaymentOrderListener
import dev.sumin.skeleton.payment.PaymentOrders
import dev.sumin.skeleton.payment.PaymentProperties
import dev.sumin.skeleton.payment.PaymentProviderRouter
import dev.sumin.skeleton.payment.PaymentRefund
import dev.sumin.skeleton.payment.PaymentRefundApplier
import dev.sumin.skeleton.payment.PaymentRefundGateway
import dev.sumin.skeleton.payment.PaymentRefundRequest
import dev.sumin.skeleton.payment.PaymentRefunds
import dev.sumin.skeleton.payment.PaymentSnapshot
import dev.sumin.skeleton.payment.PaymentTask
import dev.sumin.skeleton.payment.PaymentWebhookGateway
import dev.sumin.skeleton.payment.PaymentWebhookInbox
import dev.sumin.skeleton.payment.PaymentWebhookTaskHandler
import dev.sumin.skeleton.payment.RefundApplyTaskHandler
import dev.sumin.skeleton.payment.RefundDeclineCategory
import dev.sumin.skeleton.payment.RefundReconcileTaskHandler
import dev.sumin.skeleton.payment.WebhookDelivery
import dev.sumin.skeleton.payment.WebhookEnvelope
import dev.sumin.skeleton.payment.WebhookReceipt
import dev.sumin.skeleton.payment.testing.CheerCatalog
import dev.sumin.skeleton.payment.testing.MutableTime
import dev.sumin.skeleton.payment.testing.RecordingOrderListener
import dev.sumin.skeleton.payment.testing.RecordingReviewNotifier
import dev.sumin.skeleton.payment.testing.ScriptedGateway
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicInteger
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate

/**
 * 결제사 한 곳이 여러 백엔드 앞에 있는 모양 — 결제사는 바깥에 하나뿐이라 호출 수를 세려면 [shared] 하나를 함께 봐야 한다.
 * 백엔드마다 이 얇은 위임 게이트웨이를 두고, **자기 저장소의 열린 트랜잭션**을 보게 한다: 트랜잭션 안에서 결제사를 부르면 [shared] 가 아니라 이 감시가 던진다
 * (`ScriptedGateway` 의 감시는 생성자에서 정해져 한 저장소에만 걸린다).
 */
class ProviderLink(
    private val sharedProvider: () -> ScriptedGateway,
    private val openTransaction: () -> Boolean,
) : PaymentConfirmGateway, PaymentLookupGateway, PaymentCheckoutGateway, PaymentRefundGateway, PaymentWebhookGateway {
    private val shared: ScriptedGateway get() = sharedProvider()

    override val providerId: String get() = shared.providerId
    override val supportedCurrencies: Set<String> get() = shared.supportedCurrencies
    override val mode: PaymentMode get() = shared.mode
    override val signed: Boolean get() = shared.signed

    private fun guard(call: String) {
        if (openTransaction()) throw AssertionError("$call was called inside an open ledger transaction")
    }

    override fun acceptsPaymentKey(providerPaymentId: String): Boolean = shared.acceptsPaymentKey(providerPaymentId)

    override fun confirm(request: PaymentConfirmRequest): PaymentOperationResult = guard("confirm").let { shared.confirm(request) }

    override fun lookup(query: PaymentLookupQuery): PaymentSnapshot? = guard("lookup").let { shared.lookup(query) }

    override fun refund(request: PaymentRefundRequest): PaymentOperationResult = guard("refund").let { shared.refund(request) }

    override fun refundDeclineCategory(providerCode: String?): RefundDeclineCategory = shared.refundDeclineCategory(providerCode)

    override fun checkout(order: CheckoutOrder): CheckoutInstruction = guard("checkout").let { shared.checkout(order) }

    override fun parse(delivery: WebhookDelivery): WebhookEnvelope = shared.parse(delivery)
}

/** 지급 훅 — 백엔드마다 자기 기록을 갖되, 앞쪽 [plantedFailures] 번(클러스터 전체에서 합쳐)은 던진다. */
class InstanceListener(private val recorded: RecordingOrderListener, private val plantedFailures: AtomicInteger) : PaymentOrderListener {
    override fun onPaid(order: PaymentOrder) {
        if (plantedFailures.getAndUpdate { if (it > 0) it - 1 else it } > 0) error("planted listener failure")
        recorded.onPaid(order)
    }

    override fun onRefunded(order: PaymentOrder, refund: PaymentRefund) = recorded.onRefunded(order, refund)
}

/**
 * 같은 DB 를 보는 **독립된 백엔드 한 대**: 자기 연결 풀 · 트랜잭션 관리자 · 저장소 · 작업 큐 · 서비스 묶음 · 큐 워커 · 시계 · 지급 훅.
 * 다른 백엔드와 **공유하는 객체가 없다**(결제사 [shared] 와 DB 뿐) — 프로세스 안 상태(자바 락 · 메모리 맵 · 싱글턴이 든 상태)에 기대는 코드는 여기서 어긋난다.
 * (한 JVM 안이라 `static`/`object` 의 전역 상태는 여전히 공유된다 — `docs/modules/payment-jdbc.md` 「여러 인스턴스 시험」)
 */
class LedgerInstance(
    val name: String,
    sharedProvider: () -> ScriptedGateway,
    val properties: PaymentProperties,
    plantedFailures: AtomicInteger,
    start: Instant,
    poolSize: Int = 7,
) : AutoCloseable {
    /** 이 백엔드의 도메인 시계 — 시험이 백엔드마다 따로 어긋나게 할 수 있다. */
    val time = MutableTime(start)

    /** 이 백엔드의 큐 시계 — 작업이 "지금 할 때가 됐나"를 이 시계로 본다(기본은 실제 시각). */
    @Volatile var queueSkew: Duration = Duration.ZERO
    private val queueClock = TimeProvider { TimeProvider.systemUtc().now().plus(queueSkew) }

    val dataSource: HikariDataSource = (DbTestDatabase.dataSource() as DriverManagerDataSource).let { source ->
        HikariDataSource().apply {
            jdbcUrl = source.url
            username = source.username
            password = source.password
            maximumPoolSize = poolSize
            minimumIdle = poolSize // 연결을 미리 다 열어 둔다 — 차가운 풀은 연결을 하나씩 만들어 주어 스레드들이 줄 서듯 어긋나 경주가 약해진다(변이 시험이 잡았다)
            poolName = "pool-$name"
        }
    }.also(::warmUp)
    val jdbc = NamedParameterJdbcTemplate(dataSource)
    val transactionManager = DataSourceTransactionManager(dataSource)
    val store = JdbcPaymentLedgerStore(jdbc, transactionManager, DbTestDatabase.dialect)
    val gateway = ProviderLink(sharedProvider, store::hasOpenTransaction)

    val listener = RecordingOrderListener()
    val review = RecordingReviewNotifier()
    val catalog = CheerCatalog()
    private val repository = JdbcJobRepository(JdbcClient.create(dataSource), TransactionTemplate(transactionManager), DbTestDatabase.dialect)
    val queue = JdbcJobQueue(repository, JobQueueProperties(), TimeProvider.systemUtc())
    val tasks = JdbcPaymentTasks(queue, store)
    val router = PaymentProviderRouter(listOf(gateway), properties)
    val writer = PaymentLedgerWriter(time)
    val convergence = PaymentConvergence(writer, tasks, review, LedgerRefundConvergence(writer, tasks, review))
    val fulfillment = PaymentFulfillment(store, InstanceListener(listener, plantedFailures), writer)
    val orders = PaymentOrders(store, tasks, router, catalog, convergence, fulfillment, writer, properties)
    val expire = OrderExpireTaskHandler(store, router, convergence)
    val reconcile = OrderReconcileTaskHandler(store, router, convergence)
    val fulfil = OrderFulfilTaskHandler(fulfillment, store, convergence)
    val applier = PaymentRefundApplier(store, InstanceListener(listener, plantedFailures), fulfillment, writer)
    val refunds = PaymentRefunds(store, tasks, router, convergence, applier, writer, properties)
    val refundReconcile = RefundReconcileTaskHandler(store, router, convergence, writer, properties)
    val refundApply = RefundApplyTaskHandler(applier, store, convergence)
    val inbox = PaymentWebhookInbox(store, tasks, router, writer, properties)
    val webhook = PaymentWebhookTaskHandler(store, router, convergence, writer)
    val runner = DefaultPaymentTaskRunner(listOf(expire, reconcile, fulfil, refundReconcile, refundApply, webhook))

    /** 진짜 큐 워커(우리 작업 처리기 여섯 종 · 죽은 작업 고리). 폴링 스레드는 켜지 않고 시험이 [JobQueueWorker.pollOnce] 를 부른다. */
    val worker = worker(runner.types.map { PaymentJobHandler(it, runner) }, JobQueueProperties(workerId = "worker-$name"))

    fun worker(handlers: List<JobHandler>, properties: JobQueueProperties = JobQueueProperties(workerId = "worker-$name")) =
        JobQueueWorker(repository, handlers, properties, queueClock, deadListeners = listOf(PaymentJobDeadListener { runner }))

    /** 데드락으로 진 폴링 — 운영의 워커가 `safePoll` 로 삼키고 다음 폴링에 다시 하는 것과 같다(여기서는 세어 둔다). */
    val pollDeadlocks = java.util.concurrent.CopyOnWriteArrayList<Exception>()

    /** 한 번 폴링한다. DB 가 교착으로 이 폴링의 트랜잭션을 되돌렸으면([org.springframework.dao.ConcurrencyFailureException]) 센 뒤 0 을 돌려준다 — 작업은 그대로 남아 다음 폴링이 집는다. */
    fun poll(): Int = try {
        worker.pollOnce()
    } catch (e: org.springframework.dao.ConcurrencyFailureException) {
        pollDeadlocks += e
        0
    }

    fun pollUntilIdle(): Int = pollUntilIdle(pollDeadlocks) { worker.pollOnce() }

    fun create(key: String, accountId: String = "acc_1", itemRef: String = "cheer-3000"): CreatedOrder =
        orders.create(CreateOrderCommand(accountId, "cheer", itemRef, IdempotencyKey(key), 1))

    fun deliver(delivery: WebhookDelivery): WebhookReceipt = inbox.receive(gateway, delivery)

    /** 작업 하나를 이 백엔드의 장부 트랜잭션 안에서 넣는다(큐는 부르는 쪽 트랜잭션에 참여한다). */
    fun enqueue(task: PaymentTask) = store.inTransaction { tasks.enqueue(task) }

    fun order(id: String): PaymentOrder = checkNotNull(store.findOrder(id))

    override fun close() = dataSource.close()

    private fun warmUp(pool: HikariDataSource) {
        pool.connection.close() // 풀 시작
        val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
        while (pool.hikariPoolMXBean.totalConnections < pool.maximumPoolSize) {
            check(System.nanoTime() < deadline) { "the pool $name never filled up" }
            Thread.sleep(5)
        }
    }
}

/**
 * 백엔드 [size] 대 — 같은 DB, 서로 객체를 하나도 공유하지 않는다. 결제사 [provider] 하나만 함께 본다(바깥에 하나뿐이다).
 * 시험 하나(라운드 하나)에 클러스터 하나를 만들고 닫는다.
 */
class LedgerCluster(
    val size: Int = 3,
    val properties: PaymentProperties = PaymentProperties(webhook = PaymentProperties.Webhook(pathToken = "cluster-webhook-token-0123456789abcdef")),
) : AutoCloseable {
    /** 결제사 흉내(바깥에 하나) — 라운드마다 새로 바뀐다([reset]). */
    @Volatile var provider = ScriptedGateway()
        private set

    /** 앞쪽 N 번의 지급 훅 호출(백엔드 무관)이 던진다 — 지급 작업 경로를 만들어 낸다. */
    val plantedPaidFailures = AtomicInteger()

    /** 이번 라운드가 시작한 실제 시각(밀리초) — 백엔드의 시계는 여기서 시작한다. */
    @Volatile var start: Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        private set

    val instances: List<LedgerInstance> = List(size) { LedgerInstance("be-$it", { provider }, properties, plantedPaidFailures, start) }

    init {
        assertNothingShared()
    }

    /**
     * 라운드 하나를 시작한다 — 표를 비우고, 결제사 · 시계 · 기록을 새것으로. 백엔드(풀 · 저장소 · 서비스)는 그대로다.
     * 라운드마다 클러스터를 새로 만들면 차가운 풀이 연결을 하나씩 만들어 주어 스레드들이 줄 서듯 어긋나 경주가 약해지고 시험이 몇 배 느려진다.
     */
    fun rounds(count: Int, body: (Int) -> Unit) = repeat(count) { round ->
        PaymentDb.clean()
        provider = ScriptedGateway()
        plantedPaidFailures.set(0)
        start = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        instances.forEach { inst ->
            inst.time.set(start)
            inst.queueSkew = Duration.ZERO
            inst.listener.paid.clear()
            inst.listener.refunded.clear()
            inst.listener.calls.clear()
            inst.review.flagged.clear()
            inst.catalog.quotes.clear()
        }
        body(round)
    }

    /** 백엔드들이 객체를 하나도 공유하지 않는다 — 풀 · 저장소 · 서비스 · 워커 · 시계 · 훅 모두 서로 다른 객체다(결제사 [provider] 만 함께 본다). */
    private fun assertNothingShared() {
        fun parts(i: LedgerInstance): List<Any> = listOf(
            i.dataSource, i.jdbc, i.transactionManager, i.store, i.gateway, i.time, i.listener, i.review, i.catalog, i.queue, i.tasks, i.router, i.writer, i.convergence, i.fulfillment,
            i.orders, i.expire, i.reconcile, i.fulfil, i.applier, i.refunds, i.refundReconcile, i.refundApply, i.inbox, i.webhook, i.runner, i.worker,
        )
        instances.forEachIndexed { a, left ->
            check((left.gateway as Any) !== provider) { "${left.name} must reach the provider through its own link" }
            instances.drop(a + 1).forEach { right ->
                parts(left).zip(parts(right)).forEach { (l, r) -> check(l !== r) { "${left.name} and ${right.name} share a ${l.javaClass.simpleName}" } }
                check(left.dataSource.poolName != right.dataSource.poolName)
            }
        }
    }

    /** 이 클러스터의 폴링이 DB 교착으로 되돌려진 횟수(모든 백엔드 합) — 보고용. */
    val pollDeadlocks: Int get() = instances.sumOf { it.pollDeadlocks.size }

    val listenersPaid: Int get() = instances.sumOf { it.listener.paid.size }
    val listenersRefunded: Int get() = instances.sumOf { it.listener.refunded.size }

    /** 백엔드마다 [threadsPerInstance] 스레드, 전부 한 순간에 출발한다. 결과는 (몇 번째 백엔드, 결과) 짝. */
    fun <T> raceAcross(threadsPerInstance: Int, block: (LedgerInstance, Int) -> T): List<Pair<LedgerInstance, Result<T>>> {
        val results = race(size * threadsPerInstance) { i -> block(instances[i % size], i / size) }
        return results.mapIndexed { i, result -> instances[i % size] to result }
    }

    /** 모든 백엔드의 워커가 같은 순간에 폴링한다 — 더 처리할 것이 없을 때까지(재시도 대기에 든 작업은 기다리지 않는다). 처리한 작업 수를 돌려준다. */
    fun drainWorkers(maxRounds: Int = 20): Int {
        var total = 0
        repeat(maxRounds) {
            val deadlocksBefore = instances.sumOf { it.pollDeadlocks.size }
            val processed = race(size) { n -> instances[n].poll() }.sumOf { it.getOrThrow() }
            total += processed
            if (processed == 0 && instances.sumOf { it.pollDeadlocks.size } == deadlocksBefore) return total
        }
        error("the workers kept finding work after $maxRounds rounds")
    }

    /** 모든 백엔드의 시계를 같은 시각으로 맞춘다. */
    fun setClocks(instant: Instant) = instances.forEach { it.time.set(instant) }

    /** 첫 백엔드가 주문을 만들고 결제창 인증까지 마친 상태로 만든다. */
    fun createAuthenticated(key: String, paymentKey: String, accountId: String = "acc_1"): PaymentOrder {
        val created = instances[0].create(key, accountId).order
        provider.authenticate(paymentKey, created.id, created.amount)
        return created
    }

    /** 첫 백엔드가 결제까지 끝낸 주문 하나(3,000원). 지급 훅 기록은 비워 둔다. */
    fun paid(key: String, paymentKey: String): PaymentOrder {
        val created = createAuthenticated(key, paymentKey)
        val order = instances[0].orders.confirm("acc_1", created.id, paymentKey, created.amount)
        check(order.status.isPaidFamily)
        instances.forEach { it.listener.paid.clear(); it.listener.calls.clear() }
        return order
    }

    override fun close() {
        if (pollDeadlocks > 0) println("MEASURED job-queue poll rolled back by a database deadlock (${DbTestDatabase.vendor}): ${pollDeadlocks}x - ${instances.flatMap { it.pollDeadlocks }.firstOrNull()?.message?.take(160)}")
        instances.forEach { it.close() }
    }
}

/**
 * 더 처리할 것이 없을 때까지 폴링한다. DB 교착으로 되돌려진 폴링([org.springframework.dao.ConcurrencyFailureException])은 빈 폴링으로 치지 않고 [deadlocks] 에 센 뒤 다시 한다
 * (운영의 워커는 `safePoll` 이 삼키고 다음 주기에 다시 한다 — 되돌려진 폴링의 작업은 `PENDING` 으로 남는다).
 */
fun pollUntilIdle(deadlocks: MutableList<Exception>, poll: () -> Int): Int {
    var total = 0
    repeat(1000) {
        val processed = try {
            poll()
        } catch (e: org.springframework.dao.ConcurrencyFailureException) {
            deadlocks += e
            -1
        }
        if (processed == 0) return total
        if (processed > 0) total += processed
    }
    error("the worker kept finding work")
}
