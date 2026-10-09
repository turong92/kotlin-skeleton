package dev.sumin.skeleton.payment.testing

import dev.sumin.skeleton.payment.OperatorOrderFilter
import dev.sumin.skeleton.payment.OperatorOrderScope
import dev.sumin.skeleton.payment.OrderCursor
import dev.sumin.skeleton.payment.PaymentEvent
import dev.sumin.skeleton.payment.PaymentLedgerConflictException
import dev.sumin.skeleton.payment.PaymentLedgerStore
import dev.sumin.skeleton.payment.PaymentLedgerTx
import dev.sumin.skeleton.payment.PaymentOrder
import dev.sumin.skeleton.payment.PaymentOrderStatus
import dev.sumin.skeleton.payment.PaymentOriginType
import dev.sumin.skeleton.payment.PaymentSubscription
import dev.sumin.skeleton.payment.SubscriptionStatus
import dev.sumin.skeleton.payment.PaymentRefund
import dev.sumin.skeleton.payment.PaymentRefundStatus
import dev.sumin.skeleton.payment.RefundCursor
import dev.sumin.skeleton.payment.PaymentWebhookEvent
import dev.sumin.skeleton.payment.PaymentWebhookStatus
import dev.sumin.skeleton.payment.RecordedWebhookDelivery
import dev.sumin.skeleton.payment.WebhookEventCursor
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/**
 * 시험용 메모리 [PaymentLedgerStore] — **잠금 의미를 흉내 낸다**: 주문 줄 잠금은 줄마다 하나의 락이라 같은 줄을 잠그려는 두 트랜잭션은 차례로 돈다
 * (잠근 뒤 줄을 **다시 읽는다**), 쓰기는 커밋에서야 보이고 롤백이면 사라진다, 같은 `dedupeKey` · `(provider, provider_payment_id)` 로 넣으려는 쪽은
 * 앞선 트랜잭션이 끝날 때까지 기다린다(DB 유니크 인덱스처럼). 그래서 동시성 시험이 뜻이 있다. 진짜 DB 의 경합은 `payment-jdbc` 의 dbTest 가 잰다.
 *
 * 제품 코드가 아니다 — 재시작에 사라진다.
 */
class InMemoryPaymentLedgerStore : PaymentLedgerStore {
    private val commitMutex = Any()
    private val committed = LinkedHashMap<String, PaymentOrder>()
    private val committedEvents = ArrayList<PaymentEvent>()
    private val committedRefunds = LinkedHashMap<String, PaymentRefund>()
    private val committedWebhooks = LinkedHashMap<Long, PaymentWebhookEvent>()
    private val committedSubscriptions = LinkedHashMap<String, PaymentSubscription>()
    private val webhookIds = AtomicLong()
    private val rowLocks = ConcurrentHashMap<String, ReentrantLock>()
    private val keyLocks = ConcurrentHashMap<String, ReentrantLock>()
    private val current = ThreadLocal<Tx?>()

    /** 이 스레드가 열어 둔 트랜잭션 — [InMemoryPaymentTasks] 가 작업을 거기에 얹는다. */
    internal fun currentTransaction(): Tx? = current.get()

    override fun <T> inTransaction(block: (PaymentLedgerTx) -> T): T {
        current.get()?.let { return block(it) } // 이미 열려 있으면 합류(스프링의 REQUIRED 처럼)
        val tx = Tx()
        current.set(tx)
        try {
            val result = block(tx)
            tx.commit()
            return result
        } catch (e: Throwable) {
            tx.rollback()
            throw e
        } finally {
            current.remove()
            tx.releaseLocks()
        }
    }

    override fun hasOpenTransaction(): Boolean = current.get() != null

    override fun findOrder(id: String): PaymentOrder? = synchronized(commitMutex) { committed[id] }

    override fun findOrderByDedupeKey(dedupeKey: String): PaymentOrder? =
        synchronized(commitMutex) { committed.values.firstOrNull { it.dedupeKey == dedupeKey } }

    override fun listEvents(orderId: String): List<PaymentEvent> =
        synchronized(commitMutex) { committedEvents.filter { it.orderId == orderId } }

    override fun findRefund(id: String): PaymentRefund? = synchronized(commitMutex) { committedRefunds[id] }

    override fun findRefundByDedupeKey(dedupeKey: String): PaymentRefund? =
        synchronized(commitMutex) { committedRefunds.values.firstOrNull { it.dedupeKey == dedupeKey } }

    override fun listRefunds(orderId: String): List<PaymentRefund> =
        synchronized(commitMutex) { committedRefunds.values.filter { it.orderId == orderId }.sortedWith(REFUND_ORDER) }

    override fun findOrderByProviderPayment(provider: String, providerPaymentId: String): PaymentOrder? =
        synchronized(commitMutex) { committed.values.firstOrNull { it.provider == provider && it.providerPaymentId == providerPaymentId } }

    override fun findWebhookEvent(id: Long): PaymentWebhookEvent? = synchronized(commitMutex) { committedWebhooks[id] }

    override fun findWebhookEventByKey(provider: String, eventKey: String): PaymentWebhookEvent? =
        synchronized(commitMutex) { committedWebhooks.values.firstOrNull { it.provider == provider && it.eventKey == eventKey } }

    override fun listWebhookEvents(status: PaymentWebhookStatus?, before: WebhookEventCursor?, limit: Int): List<PaymentWebhookEvent> =
        synchronized(commitMutex) {
            if (status == null) {
                // 상태를 정하지 않으면 id(PK) 순 — 커서는 id
                committedWebhooks.values.filter { before == null || it.id < before.id }.sortedByDescending { it.id }.take(limit)
            } else {
                committedWebhooks.values
                    .filter { it.status == status }
                    .filter { before == null || it.receivedAt < before.receivedAt || (it.receivedAt == before.receivedAt && it.id < before.id) }
                    .sortedWith(compareByDescending<PaymentWebhookEvent> { it.receivedAt }.thenByDescending { it.id })
                    .take(limit)
            }
        }

    override fun listAccountOrders(accountId: String, before: OrderCursor?, limit: Int): List<PaymentOrder> =
        synchronized(commitMutex) {
            committed.values.filter { it.accountId == accountId && it.status != PaymentOrderStatus.PENDING }.newestFirst(before).take(limit)
        }

    override fun listOrdersForOperator(scope: OperatorOrderScope, filter: OperatorOrderFilter, threshold: Instant?, before: OrderCursor?, limit: Int): List<PaymentOrder> =
        synchronized(commitMutex) {
            if (scope.needsThreshold) requireNotNull(threshold) { "$scope needs a threshold" }
            val staleRefundOrders = if (scope == OperatorOrderScope.STALE_REFUND) committedRefunds.values.filter { it.status == PaymentRefundStatus.PENDING && it.sentAt?.isBefore(threshold) == true }.map { it.orderId }.toSet() else emptySet()
            val unappliedOrders = if (scope == OperatorOrderScope.UNAPPLIED_REFUND) {
                committedRefunds.values.filter { it.status == PaymentRefundStatus.SUCCEEDED && it.appliedAt == null && (it.completedAt == null || it.completedAt.isBefore(threshold)) }.map { it.orderId }.toSet()
            } else {
                emptySet()
            }
            committed.values
                .filter { order ->
                    when (scope) {
                        OperatorOrderScope.ALL -> true
                        OperatorOrderScope.REVIEW_REASON -> order.reviewReason != null
                        OperatorOrderScope.STALE_CONFIRMING -> order.status == PaymentOrderStatus.CONFIRMING && order.confirmStartedAt?.isBefore(threshold) == true
                        OperatorOrderScope.UNFULFILLED ->
                            (order.status == PaymentOrderStatus.PAID || order.status == PaymentOrderStatus.PARTIALLY_REFUNDED) &&
                                order.fulfilledAt == null && (order.paidAt == null || order.paidAt.isBefore(threshold))
                        OperatorOrderScope.STALE_REFUND -> order.id in staleRefundOrders
                        OperatorOrderScope.UNAPPLIED_REFUND -> order.id in unappliedOrders
                    }
                }
                .filter { filter.status == null || it.status == filter.status }
                .filter { filter.accountId == null || it.accountId == filter.accountId }
                .filter { !filter.hideErased || it.accountErasedAt == null }
                .newestFirst(before)
                .take(limit)
        }

    override fun findOrdersByProviderPayment(providers: Collection<String>, providerPaymentId: String): List<PaymentOrder> =
        synchronized(commitMutex) { committed.values.filter { it.provider in providers && it.providerPaymentId == providerPaymentId }.newestFirst(null) }

    override fun listOrdersByIds(ids: Collection<String>): List<PaymentOrder> =
        synchronized(commitMutex) { committed.values.filter { it.id in ids }.newestFirst(null) }

    override fun listRefundsOfOrders(orderIds: Collection<String>): List<PaymentRefund> =
        synchronized(commitMutex) { committedRefunds.values.filter { it.orderId in orderIds }.sortedWith(REFUND_ORDER) }

    override fun listRefundsByStatus(status: PaymentRefundStatus, before: RefundCursor?, limit: Int): List<PaymentRefund> =
        synchronized(commitMutex) { committedRefunds.values.filter { it.status == status }.refundsNewestFirst(before).take(limit) }

    override fun findSubscription(id: String): PaymentSubscription? = synchronized(commitMutex) { committedSubscriptions[id] }

    override fun findLiveSubscription(accountId: String): PaymentSubscription? =
        synchronized(commitMutex) { committedSubscriptions.values.firstOrNull { it.activeKey == accountId } }

    override fun findLatestSubscription(accountId: String): PaymentSubscription? =
        synchronized(commitMutex) {
            committedSubscriptions.values
                .filter { it.accountId == accountId && (it.status != SubscriptionStatus.PENDING || it.chargeOrderId != null) }
                .maxWithOrNull(compareBy<PaymentSubscription> { it.createdAt }.thenBy { it.id })
        }

    override fun listSubscriptionOrders(subscriptionId: String, limit: Int): List<PaymentOrder> =
        synchronized(commitMutex) {
            committed.values.filter { it.originType == PaymentOriginType.SUBSCRIPTION && it.originRef == subscriptionId && it.status != PaymentOrderStatus.PENDING }.newestFirst(null).take(limit)
        }

    override fun listSubscriptionIdsOfAccount(accountId: String): List<String> =
        synchronized(commitMutex) { committedSubscriptions.values.filter { it.accountId == accountId }.map { it.id }.sorted() }

    override fun listOrderIdsOfAccount(accountId: String): List<String> =
        synchronized(commitMutex) { committed.values.filter { it.accountId == accountId }.map { it.id }.sorted() }

    override fun eraseAccountOrders(accountId: String, erasedAt: Instant): Int =
        synchronized(commitMutex) {
            var changed = 0
            committed.values.filter { it.accountId == accountId && (it.accountErasedAt == null || it.cardIssuer != null || it.cardLast4 != null) }.forEach { order ->
                committed[order.id] = order.copy(accountErasedAt = order.accountErasedAt ?: erasedAt, cardIssuer = null, cardLast4 = null)
                changed++
            }
            changed
        }

    override fun replaceAccountReferences(accountId: String, replacement: String): Int =
        synchronized(commitMutex) {
            val ids = committed.values.filter { it.accountId == accountId }.map { it.id }.toSet()
            if (ids.isEmpty() && committedSubscriptions.values.none { it.accountId == accountId }) return 0
            var changed = 0
            committedEvents.indices.forEach { i ->
                val event = committedEvents[i]
                if (event.orderId in ids && event.actorId == accountId) {
                    committedEvents[i] = event.copy(actorId = replacement)
                    changed++
                }
            }
            committedRefunds.values.filter { it.orderId in ids && (it.requestedBy == accountId || it.decidedBy == accountId) }.forEach { refund ->
                committedRefunds[refund.id] = refund.copy(
                    requestedBy = if (refund.requestedBy == accountId) replacement else refund.requestedBy,
                    decidedBy = if (refund.decidedBy == accountId) replacement else refund.decidedBy,
                )
                changed++
            }
            committedSubscriptions.values.filter { it.accountId == accountId }.forEach { sub ->
                committedSubscriptions[sub.id] = sub.copy(accountId = replacement, activeKey = if (sub.activeKey == accountId) replacement else sub.activeKey)
                changed++
            }
            ids.forEach { id -> committed[id] = committed.getValue(id).copy(accountId = replacement) ; changed++ }
            changed
        }

    private fun List<PaymentOrder>.newestFirst(before: OrderCursor?): List<PaymentOrder> =
        sortedWith(compareByDescending<PaymentOrder> { it.createdAt }.thenByDescending { it.id })
            .filter { before == null || it.createdAt < before.createdAt || (it.createdAt == before.createdAt && it.id < before.id) }

    private fun List<PaymentRefund>.refundsNewestFirst(before: RefundCursor?): List<PaymentRefund> =
        sortedWith(compareByDescending<PaymentRefund> { it.requestedAt }.thenByDescending { it.id })
            .filter { before == null || it.requestedAt < before.requestedAt || (it.requestedAt == before.requestedAt && it.id < before.id) }

    /** 커밋된 모든 줄 — 시험이 상태를 한눈에 보려고. */
    fun allOrders(): List<PaymentOrder> = synchronized(commitMutex) { committed.values.toList() }

    inner class Tx : PaymentLedgerTx {
        private val pending = LinkedHashMap<String, PaymentOrder>()
        private val pendingRefunds = LinkedHashMap<String, PaymentRefund>()
        private val pendingEvents = ArrayList<PaymentEvent>()
        private val pendingWebhooks = LinkedHashMap<Long, PaymentWebhookEvent>()
        private val pendingSubscriptions = LinkedHashMap<String, PaymentSubscription>()
        private var ownedSubscriptionId: String? = null
        private var ownedWebhookId: Long? = null
        private val ownedOrderIds = LinkedHashSet<String>()
        private val heldLocks = ArrayList<ReentrantLock>()
        private val onCommit = ArrayList<() -> Unit>()

        internal fun afterCommit(action: () -> Unit) {
            onCommit += action
        }

        override fun lockOrder(id: String): PaymentOrder? {
            if (id in ownedOrderIds) return view(id)
            check(ownedOrderIds.isEmpty()) { "one order per transaction: already holds ${ownedOrderIds.single()}" }
            check(ownedWebhookId == null) { "lock order is order, then webhook row: this transaction already holds a webhook row" }
            acquire(rowLocks.computeIfAbsent(id) { ReentrantLock() })
            val row = view(id)
            if (row != null) ownedOrderIds += id
            return row
        }

        override fun findOrderByDedupeKey(dedupeKey: String): PaymentOrder? =
            pending.values.firstOrNull { it.dedupeKey == dedupeKey } ?: this@InMemoryPaymentLedgerStore.findOrderByDedupeKey(dedupeKey)

        override fun findOrderByProviderPayment(provider: String, providerPaymentId: String): PaymentOrder? =
            pending.values.firstOrNull { it.provider == provider && it.providerPaymentId == providerPaymentId }
                ?: synchronized(commitMutex) {
                    committed.values.firstOrNull { it.provider == provider && it.providerPaymentId == providerPaymentId && it.id !in pending }
                }

        override fun insertOrderIfAbsent(order: PaymentOrder): Boolean {
            check(ownedOrderIds.isEmpty()) { "one order per transaction: already holds ${ownedOrderIds.single()}" }
            order.dedupeKey?.let { key ->
                acquire(keyLocks.computeIfAbsent("dedupe|$key") { ReentrantLock() })
                if (findOrderByDedupeKey(key) != null) return false
            }
            acquire(rowLocks.computeIfAbsent(order.id) { ReentrantLock() })
            check(view(order.id) == null) { "duplicate order id" }
            pending[order.id] = order
            ownedOrderIds += order.id
            return true
        }

        override fun updateOrder(order: PaymentOrder) {
            check(order.id in ownedOrderIds) { "the order must be locked in this transaction before it is updated" }
            order.providerPaymentId?.let { paymentId ->
                acquire(keyLocks.computeIfAbsent("payment|${order.provider}|$paymentId") { ReentrantLock() })
                val owner = findOrderByProviderPayment(order.provider, paymentId)
                if (owner != null && owner.id != order.id) throw PaymentLedgerConflictException("provider payment id is already attached to another order")
            }
            pending[order.id] = order
        }

        override fun appendEvent(event: PaymentEvent) {
            pendingEvents += event
        }

        override fun lockRefund(id: String): PaymentRefund? {
            val refund = refundView(id) ?: return null
            check(refund.orderId in ownedOrderIds) { "the refund's order must be locked in this transaction first (order, then refund)" }
            return refund
        }

        override fun findRefundByDedupeKey(dedupeKey: String): PaymentRefund? =
            pendingRefunds.values.firstOrNull { it.dedupeKey == dedupeKey } ?: this@InMemoryPaymentLedgerStore.findRefundByDedupeKey(dedupeKey)

        override fun listRefunds(orderId: String): List<PaymentRefund> {
            check(orderId in ownedOrderIds) { "the order must be locked in this transaction before its refunds are listed" }
            return refundsOf(orderId)
        }

        override fun insertRefundIfAbsent(refund: PaymentRefund): Boolean {
            check(refund.orderId in ownedOrderIds) { "the refund's order must be locked in this transaction before a refund is inserted" }
            refund.dedupeKey?.let { key ->
                acquire(keyLocks.computeIfAbsent("refund-dedupe|$key") { ReentrantLock() })
                if (findRefundByDedupeKey(key) != null) return false
            }
            val siblings = refundsOf(refund.orderId)
            if (refundView(refund.id) != null) return false
            if (refund.openKey != null && siblings.any { it.openKey == refund.openKey }) return false
            if (refund.providerRefundId != null && siblings.any { it.providerRefundId == refund.providerRefundId }) return false
            pendingRefunds[refund.id] = refund
            return true
        }

        override fun updateRefund(refund: PaymentRefund) {
            check(refund.orderId in ownedOrderIds) { "the refund's order must be locked in this transaction before a refund is updated" }
            val existing = checkNotNull(refundView(refund.id)) { "no such refund to update" }
            check(existing.orderId == refund.orderId) { "a refund never changes its order" }
            val siblings = refundsOf(refund.orderId).filter { it.id != refund.id }
            if (refund.openKey != null && siblings.any { it.openKey == refund.openKey }) throw PaymentLedgerConflictException("the order already has an open refund")
            if (refund.providerRefundId != null && siblings.any { it.providerRefundId == refund.providerRefundId }) {
                throw PaymentLedgerConflictException("the provider refund id is already attached to another refund of this order")
            }
            pendingRefunds[refund.id] = refund
        }

        override fun recordWebhookDelivery(candidate: PaymentWebhookEvent): RecordedWebhookDelivery {
            check(ownedWebhookId == null) { "one webhook row per transaction: already holds $ownedWebhookId" }
            acquire(keyLocks.computeIfAbsent("webhook|${candidate.provider}|${candidate.eventKey}") { ReentrantLock() })
            val existing = (pendingWebhooks.values + synchronized(commitMutex) { committedWebhooks.values.toList() }).firstOrNull { it.provider == candidate.provider && it.eventKey == candidate.eventKey }
            val row = if (existing == null) candidate.copy(id = webhookIds.incrementAndGet(), deliveries = 1) else existing.copy(deliveries = existing.deliveries + 1)
            pendingWebhooks[row.id] = row
            ownedWebhookId = row.id
            return RecordedWebhookDelivery(row, first = existing == null)
        }

        override fun countWebhookEventsOfOrder(orderId: String, resourceType: String, since: Instant): Int {
            check(orderId in ownedOrderIds) { "the order must be locked in this transaction before its webhook rows are counted" }
            val rows = LinkedHashMap<Long, PaymentWebhookEvent>()
            synchronized(commitMutex) { rows.putAll(committedWebhooks) }
            rows.putAll(pendingWebhooks)
            return rows.values.count { it.orderId == orderId && it.resourceType == resourceType && it.receivedAt >= since }
        }

        override fun lockWebhookEvent(id: Long): PaymentWebhookEvent? {
            if (id == ownedWebhookId) return webhookView(id)
            check(ownedWebhookId == null) { "one webhook row per transaction: already holds $ownedWebhookId" }
            val row = webhookView(id) ?: return null
            acquire(keyLocks.computeIfAbsent("webhook|${row.provider}|${row.eventKey}") { ReentrantLock() })
            ownedWebhookId = id
            return webhookView(id) // 기다린 뒤에는 다시 읽는다
        }

        override fun updateWebhookEvent(event: PaymentWebhookEvent) {
            check(event.id == ownedWebhookId) { "the webhook row must be recorded or locked in this transaction before it is updated" }
            val stored = checkNotNull(webhookView(event.id))
            pendingWebhooks[event.id] = stored.copy(status = event.status, lastError = event.lastError, processedAt = event.processedAt)
        }

        override fun lockSubscription(id: String): PaymentSubscription? {
            if (id == ownedSubscriptionId) return subscriptionView(id)
            check(ownedSubscriptionId == null) { "one subscription per transaction: already holds $ownedSubscriptionId" }
            acquire(rowLocks.computeIfAbsent("subscription|$id") { ReentrantLock() })
            val row = subscriptionView(id)
            if (row != null) ownedSubscriptionId = id
            return row
        }

        override fun insertSubscriptionIfAbsent(subscription: PaymentSubscription): Boolean {
            check(ownedSubscriptionId == null) { "one subscription per transaction: already holds $ownedSubscriptionId" }
            acquire(rowLocks.computeIfAbsent("subscription|${subscription.id}") { ReentrantLock() })
            if (subscriptionView(subscription.id) != null) return false
            checkActiveKey(subscription)
            pendingSubscriptions[subscription.id] = subscription
            ownedSubscriptionId = subscription.id
            return true
        }

        override fun updateSubscription(subscription: PaymentSubscription) {
            check(subscription.id == ownedSubscriptionId) { "the subscription must be locked in this transaction before it is updated" }
            checkActiveKey(subscription)
            pendingSubscriptions[subscription.id] = subscription
        }

        /** `active_key` 유니크 — 같은 키를 쥐려는 쪽은 앞선 트랜잭션이 끝날 때까지 기다렸다가(DB 유니크 인덱스처럼) 겹치면 충돌이다. */
        private fun checkActiveKey(subscription: PaymentSubscription) {
            val key = subscription.activeKey ?: return
            acquire(keyLocks.computeIfAbsent("subscription-active|$key") { ReentrantLock() })
            val owner = pendingSubscriptions.values.firstOrNull { it.activeKey == key && it.id != subscription.id }
                ?: synchronized(commitMutex) { committedSubscriptions.values.firstOrNull { it.activeKey == key && it.id != subscription.id && it.id !in pendingSubscriptions } }
            if (owner != null) throw PaymentLedgerConflictException("the account already has a live subscription")
        }

        private fun subscriptionView(id: String): PaymentSubscription? = pendingSubscriptions[id] ?: synchronized(commitMutex) { committedSubscriptions[id] }

        private fun webhookView(id: Long): PaymentWebhookEvent? = pendingWebhooks[id] ?: synchronized(commitMutex) { committedWebhooks[id] }

        private fun refundView(id: String): PaymentRefund? = pendingRefunds[id] ?: synchronized(commitMutex) { committedRefunds[id] }

        /** 이 트랜잭션이 보는 한 주문의 환불 줄(내 쓰기가 커밋된 것을 덮는다), 오래된 순. */
        private fun refundsOf(orderId: String): List<PaymentRefund> {
            val committed = synchronized(commitMutex) { committedRefunds.values.filter { it.orderId == orderId && it.id !in pendingRefunds } }
            return (committed + pendingRefunds.values.filter { it.orderId == orderId }).sortedWith(REFUND_ORDER)
        }

        private fun view(id: String): PaymentOrder? = pending[id] ?: synchronized(commitMutex) { committed[id] }

        private fun acquire(lock: ReentrantLock) {
            lock.lock()
            heldLocks += lock
        }

        internal fun commit() {
            val hooks = synchronized(commitMutex) {
                committed.putAll(pending)
                committedRefunds.putAll(pendingRefunds)
                committedWebhooks.putAll(pendingWebhooks)
                committedSubscriptions.putAll(pendingSubscriptions)
                committedEvents += pendingEvents
                onCommit.toList()
            }
            hooks.forEach { it() }
        }

        internal fun rollback() {
            pending.clear()
            pendingRefunds.clear()
            pendingWebhooks.clear()
            pendingSubscriptions.clear()
            pendingEvents.clear()
            onCommit.clear()
        }

        internal fun releaseLocks() {
            heldLocks.asReversed().forEach { it.unlock() }
            heldLocks.clear()
        }
    }

    private companion object {
        /** 오래된 순 — 만든 시각, 같으면 id (JDBC 저장소의 `order by created_at, id` 와 같다). */
        val REFUND_ORDER: Comparator<PaymentRefund> = compareBy<PaymentRefund> { it.createdAt }.thenBy { it.id }
    }
}
