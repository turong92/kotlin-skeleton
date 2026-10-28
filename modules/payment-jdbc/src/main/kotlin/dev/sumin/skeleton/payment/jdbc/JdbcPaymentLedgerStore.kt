package dev.sumin.skeleton.payment.jdbc

import dev.sumin.skeleton.payment.OperatorOrderFilter
import dev.sumin.skeleton.payment.OperatorOrderScope
import dev.sumin.skeleton.payment.OrderCursor
import dev.sumin.skeleton.payment.OrderDiscount
import dev.sumin.skeleton.payment.OrderDiscountStatus
import dev.sumin.skeleton.payment.PaymentOrders
import dev.sumin.skeleton.payment.PaymentSettlement
import dev.sumin.skeleton.payment.PaymentEvent
import dev.sumin.skeleton.payment.PaymentLedgerConflictException
import dev.sumin.skeleton.payment.PaymentLedgerStore
import dev.sumin.skeleton.payment.PaymentLedgerTx
import dev.sumin.skeleton.payment.PaymentOrder
import dev.sumin.skeleton.payment.PaymentOrderStatus
import dev.sumin.skeleton.payment.PaymentRefund
import dev.sumin.skeleton.payment.PaymentRefundStatus
import dev.sumin.skeleton.payment.PaymentSubscription
import dev.sumin.skeleton.payment.SubscriptionDiscount
import dev.sumin.skeleton.payment.RefundCursor
import dev.sumin.skeleton.payment.PaymentWebhookEvent
import dev.sumin.skeleton.payment.PaymentWebhookStatus
import dev.sumin.skeleton.payment.RecordedWebhookDelivery
import dev.sumin.skeleton.payment.WebhookEventCursor
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.time.Instant
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate

/*
 * 규칙 (포트 KDoc `PaymentLedgerStore` · `PaymentLedgerTx` 가 정본, 스펙 §6.1):
 *  - 락은 주문 줄 하나다. [PaymentLedgerTx.lockOrder] 는 **잠그는 문장**(`select id … for update`)과 **판단하는 읽기**(새 문장 · 잠금 읽기)를 나눈다.
 *    PostgreSQL 의 `for update` 는 기다리는 동안 다른 트랜잭션이 커밋한 것을 그 문장의 스냅샷에서 놓칠 수 있다(author-profile-jdbc 의 같은 주석). 판단하는 읽기도 잠금 읽기로 해서
 *    MySQL 반복 읽기(바깥 트랜잭션이 연 경우)에서도 낡은 스냅샷이 아니라 **지금 커밋된 값**을 읽는다.
 *  - 유니크는 DB 가 가른다. 넣기는 충돌하면 아무것도 하지 않는 insert 이고(PostgreSQL `on conflict do nothing`, MySQL `SqlDialect.insertIgnore`),
 *    **넣었는지는 갱신 행 수가 아니라 id 로 다시 읽어** 판정한다(MySQL 은 이미 있는 행에도 1 을 돌려준다). 예외를 잡아 삼키는 방식은 PostgreSQL 에서 트랜잭션 전체를 abort 시킨다.
 *    같은 `dedupe_key` 를 동시에 넣으면 뒤의 쪽은 앞선 트랜잭션이 끝날 때까지 DB 가 기다리게 한다.
 *  - 한 트랜잭션에는 주문 하나(잠갔거나 넣은 것)만 — 한 블록 안에서는 락 순서가 늘 같아 교착이 없다. 잠그지 않은 줄을 바꾸려는 [PaymentLedgerTx.updateOrder] 는 던진다.
 *    **"한 트랜잭션"의 범위는 가장 바깥 [inTransaction] 블록이다 — 물리 트랜잭션이 아니다**(블록마다 [JdbcTx] 가 새로 생긴다; 안쪽 블록은 바깥 블록의 것에 합류한다).
 *    앱이 연 바깥 트랜잭션에 합류해 블록을 여러 번 열면 블록마다 다른 주문을 잠글 수 있다 — 일부러 그렇다(계정 하나의 주문 여럿을 호출자의 트랜잭션 안에서 훑는 길을 막지 않는다).
 *    그 경우 락은 블록이 아니라 **바깥 트랜잭션이 끝날 때** 풀리고 락 순서는 **호출자의 몫**이다(여러 주문을 잡으면 주문 id 오름차순으로) — 이 저장소가 막아 주지 않는다.
 *    시험: `JdbcOuterTransactionDbTest`.
 *  - 이 저장소가 여는 트랜잭션은 **READ COMMITTED** 다(author-profile-jdbc 와 같은 이유: MySQL 반복 읽기의 틈 잠금 · 낡은 스냅샷). 바깥 트랜잭션에 합류하면 바깥의 격리 수준이 그대로다.
 *  - 계정 id · 결제 키는 로그에 남기지 않는다(예외 메시지에도 싣지 않는다).
 */
class JdbcPaymentLedgerStore(
    private val jdbc: NamedParameterJdbcTemplate,
    transactionManager: PlatformTransactionManager,
    private val dialect: SqlDialect,
) : PaymentLedgerStore {
    private val rows = PaymentOrderRows(dialect)
    private val refundRows = PaymentRefundRows(dialect)
    private val subscriptionRows = PaymentSubscriptionRows(dialect)
    private val discountRows = PaymentDiscountRows(dialect)
    private val subscriptionDiscountRows = PaymentSubscriptionDiscountRows(dialect)
    private val webhook = JdbcWebhookInbox(jdbc, dialect)
    private val template = TransactionTemplate(transactionManager).also { it.isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED }

    /** 이 스레드에서 가장 바깥 [inTransaction] 이 연 트랜잭션 — 안쪽 호출은 여기에 합류한다(스프링의 REQUIRED 처럼). */
    private val active = ThreadLocal<JdbcTx?>()

    private val insertOrderSql: String = rows.orderInsertColumns.let { columns ->
        if (dialect.vendor == "postgresql") {
            // 삼키는 것은 dedupe_key 충돌뿐이다 — "이미 같은 키의 주문이 있다"의 뜻. (provider, provider_payment_id) · id 충돌은 그대로 예외로 올라온다(새 주문은 결제 키 없이 들어가므로 일어나면 버그다).
            // MySQL 의 insert ignore 는 충돌 종류를 가르지 못한다 — 그쪽은 아래 id 로 다시 읽는 판정이 "넣었는가"를 정하고, 호출자는 dedupe 키로 승자를 찾는다
            "insert into payment_orders (${columns.joinToString(", ")}) values (${columns.joinToString(", ") { ":$it" }}) on conflict (dedupe_key) do nothing"
        } else {
            dialect.insertIgnore("payment_orders", columns, listOf("id"))
        }
    }

    private val insertSubscriptionSql: String = subscriptionRows.insertColumns.let { columns ->
        if (dialect.vendor == "postgresql") {
            "insert into payment_subscriptions (${columns.joinToString(", ")}) values (${columns.joinToString(", ") { ":$it" }}) on conflict do nothing"
        } else {
            dialect.insertIgnore("payment_subscriptions", columns, listOf("id"))
        }
    }

    private val insertRefundSql: String = refundRows.refundInsertColumns.let { columns ->
        if (dialect.vendor == "postgresql") {
            // 충돌 대상을 적지 않는다: dedupe_key · open_key · (order_id, provider_refund_id) · id 어느 유니크가 겹쳐도 같은 길이다
            "insert into payment_refunds (${columns.joinToString(", ")}) values (${columns.joinToString(", ") { ":$it" }}) on conflict do nothing"
        } else {
            dialect.insertIgnore("payment_refunds", columns, listOf("id"))
        }
    }

    override fun <T> inTransaction(block: (PaymentLedgerTx) -> T): T {
        active.get()?.let { return block(it) }
        return try {
            template.execute {
                val tx = JdbcTx()
                active.set(tx)
                try {
                    block(tx)
                } catch (e: Exception) {
                    // TransactionTemplate 은 검사 예외를 UndeclaredThrowableException 으로 싼다 — 포트 계약은 "그 예외를 그대로 다시 던진다". 검사 예외만 표식에 실어 롤백시키고 밖에서 푼다
                    throw if (e is RuntimeException) e else CheckedFailure(e)
                } finally {
                    active.remove()
                }
            }
        } catch (e: CheckedFailure) {
            throw e.cause!!
        }
    }

    /** 검사 예외를 [TransactionTemplate] 의 포장 없이 롤백 경로에 태우는 운반용 — 밖에서 풀어 원래 예외를 던진다. */
    private class CheckedFailure(cause: Exception) : RuntimeException(cause)

    /** 바깥 트랜잭션(앱의 `@Transactional` 등)이어도 같은 답이다 — 결제사를 부르는 코드가 트랜잭션 안에서 불리지 않게 막는 검사의 근거. */
    override fun hasOpenTransaction(): Boolean = TransactionSynchronizationManager.isActualTransactionActive()

    override fun findOrder(id: String): PaymentOrder? =
        jdbc.query("${rows.orderSelect} where id = :id", mapOf("id" to id)) { rs, _ -> rows.order(rs) }.firstOrNull()

    override fun findOrderByDedupeKey(dedupeKey: String): PaymentOrder? =
        jdbc.query("${rows.orderSelect} where dedupe_key = :key", mapOf("key" to dedupeKey)) { rs, _ -> rows.order(rs) }.firstOrNull()

    override fun listEvents(orderId: String): List<PaymentEvent> =
        jdbc.query("${rows.eventSelect} where order_id = :order order by id", mapOf("order" to orderId)) { rs, _ -> rows.event(rs) }

    override fun listOrderDiscounts(orderIds: Collection<String>): List<OrderDiscount> {
        if (orderIds.isEmpty()) return emptyList()
        return jdbc.query("${discountRows.select} where order_id in (:ids) order by order_id, seq", mapOf("ids" to orderIds)) { rs, _ -> discountRows.line(rs) }
    }

    override fun hasPaidOrder(accountId: String): Boolean =
        jdbc.queryForList(
            "select 1 from payment_orders where account_id = :account and paid_at is not null and provider not in ('${PaymentOrders.FREE_PROVIDER}', '${PaymentOrders.EXEMPT_PROVIDER}') limit 1",
            mapOf("account" to accountId), Int::class.java,
        ).isNotEmpty()

    override fun findRefund(id: String): PaymentRefund? =
        jdbc.query("${refundRows.refundSelect} where id = :id", mapOf("id" to id)) { rs, _ -> refundRows.refund(rs) }.firstOrNull()

    override fun findRefundByDedupeKey(dedupeKey: String): PaymentRefund? =
        jdbc.query("${refundRows.refundSelect} where dedupe_key = :key", mapOf("key" to dedupeKey)) { rs, _ -> refundRows.refund(rs) }.firstOrNull()

    override fun listRefunds(orderId: String): List<PaymentRefund> =
        jdbc.query("${refundRows.refundSelect} where order_id = :order ${refundRows.refundOrder}", mapOf("order" to orderId)) { rs, _ -> refundRows.refund(rs) }

    override fun findOrderByProviderPayment(provider: String, providerPaymentId: String): PaymentOrder? =
        jdbc.query(
            "${rows.orderSelect} where provider = :provider and provider_payment_id = :payment",
            mapOf("provider" to provider, "payment" to providerPaymentId),
        ) { rs, _ -> rows.order(rs) }.firstOrNull()

    override fun findWebhookEvent(id: Long): PaymentWebhookEvent? = webhook.find(id)

    override fun findWebhookEventByKey(provider: String, eventKey: String): PaymentWebhookEvent? = webhook.findByKey(provider, eventKey)

    override fun listWebhookEvents(status: PaymentWebhookStatus?, before: WebhookEventCursor?, limit: Int): List<PaymentWebhookEvent> = webhook.list(status, before, limit)

    // --- 목록 읽기 (B7). 커서 조건은 행 값 비교(`(a, b) < (?, ?)`) 대신 풀어 쓴다 — MySQL 이 범위 접근을 쓰도록. ---

    override fun listAccountOrders(accountId: String, before: OrderCursor?, limit: Int): List<PaymentOrder> {
        val params = mutableMapOf<String, Any?>("account" to accountId, "limit" to limit)
        val cursor = orderCursor(before, params)
        return jdbc.query(
            "${rows.orderSelect} where account_id = :account and status <> 'PENDING'$cursor order by created_at desc, id desc limit :limit",
            params,
        ) { rs, _ -> rows.order(rs) }
    }

    override fun listOrdersForOperator(scope: OperatorOrderScope, filter: OperatorOrderFilter, threshold: Instant?, before: OrderCursor?, limit: Int): List<PaymentOrder> {
        if (scope == OperatorOrderScope.UNFULFILLED) {
            // 상태마다 한 문장 — 상태가 하나로 정해져야 (fulfilled_at, status, created_at, id) 인덱스가 정렬까지 맡는다. 두 상태를 `in` 으로 묶으면 정렬이 필요해져, 거의 전부 지급이 끝난 표에서
            // 계획이 created 인덱스를 거꾸로 훑으며 거르는 쪽(= 전부 읽기)을 고른다(실측: PostgreSQL 이 두 열의 상관을 모른다).
            return UNFULFILLED_STATUSES
                .filter { filter.status == null || filter.status == it }
                .flatMap { status -> operatorOrders(OperatorOrderScope.UNFULFILLED, status, filter, threshold, before, limit) }
                .sortedWith(compareByDescending<PaymentOrder> { it.createdAt }.thenByDescending { it.id })
                .take(limit)
        }
        return operatorOrders(scope, filter.status, filter, threshold, before, limit)
    }

    private fun operatorOrders(scope: OperatorOrderScope, status: PaymentOrderStatus?, filter: OperatorOrderFilter, threshold: Instant?, before: OrderCursor?, limit: Int): List<PaymentOrder> {
        val statement = orderListStatement(scope, status, filter, threshold, before, limit)
        return jdbc.query(statement.sql, statement.params) { rs, _ -> rows.order(rs) }
    }

    /** 운영자 주문 목록 문장 하나와 바인드 값 — 저장소가 보내는 그대로다. `PaymentIndexPlanDbTest` 가 이 문장을 실행 계획으로 잰다(시험이 문장을 따로 베껴 쓰면 어긋난다). 진단용 공개 함수다 — 저장소를 쓰는 쪽은 [listOrdersForOperator] 를 부른다. */
    class OrderListStatement(val sql: String, val params: Map<String, Any?>)

    fun orderListStatement(scope: OperatorOrderScope, status: PaymentOrderStatus?, filter: OperatorOrderFilter, threshold: Instant?, before: OrderCursor?, limit: Int): OrderListStatement {
        val postgres = dialect.vendor == "postgresql"
        val params = mutableMapOf<String, Any?>("limit" to limit)
        val where = mutableListOf<String>()
        when (scope) {
            OperatorOrderScope.ALL -> Unit
            OperatorOrderScope.REVIEW_REASON -> where += "review_reason is not null"
            OperatorOrderScope.STALE_CONFIRMING -> {
                where += "status = 'CONFIRMING'"
                where += "confirm_started_at < :threshold"
                params["threshold"] = dialect.instantParam(requireNotNull(threshold) { "$scope needs a threshold" })
            }
            OperatorOrderScope.UNFULFILLED -> {
                where += "fulfilled_at is null"
                where += "(paid_at is null or paid_at < :threshold)"
                params["threshold"] = dialect.instantParam(requireNotNull(threshold) { "$scope needs a threshold" })
            }
            // 환불 줄 쪽은 준조회로 — 환불 인덱스 (status, …) 가 작은 쪽(묶인 줄)에서 시작하고, 주문 쪽 필터 · 커서 · limit 는 같은 문장에서 걸린다.
            // PostgreSQL 은 `id = any(array(…))`: 배열 부분 질의는 주문 표를 읽기 **전에** 한 번 평가돼 주문은 그 id 들의 기본 키 조회로만 읽힌다(비어 있으면 주문 표를 읽지 않는다).
            // `id in (select …)` 로 쓰면 계획기가 환불 줄 수를 크게 어림해(상태와 회수 시각의 상관을 모른다) 주문을 `idx_payment_orders_created` 로 거꾸로 훑으며 환불 줄을 하나씩 찔러 본다.
            OperatorOrderScope.STALE_REFUND -> {
                val refunds = "select order_id from payment_refunds where status = 'PENDING' and sent_at < :threshold"
                where += if (postgres) "id = any(array($refunds))" else "id in ($refunds)"
                params["threshold"] = dialect.instantParam(requireNotNull(threshold) { "$scope needs a threshold" })
            }
            OperatorOrderScope.UNAPPLIED_REFUND -> {
                val refunds = "select order_id from payment_refunds where status = 'SUCCEEDED' and applied_at is null and (completed_at is null or completed_at < :threshold)"
                where += if (postgres) "id = any(array($refunds))" else "id in ($refunds)"
                params["threshold"] = dialect.instantParam(requireNotNull(threshold) { "$scope needs a threshold" })
            }
        }
        status?.let { where += "status = :status"; params["status"] = it.name }
        filter.accountId?.let { where += "account_id = :account"; params["account"] = it }
        if (filter.hideErased) where += "account_erased_at is null"
        filter.settlement?.let { settlement ->
            where += when (settlement) {
                PaymentSettlement.EXEMPT -> "provider = '${PaymentOrders.EXEMPT_PROVIDER}'"
                PaymentSettlement.FREE -> "provider = '${PaymentOrders.FREE_PROVIDER}'"
                PaymentSettlement.PROVIDER -> "provider not in ('${PaymentOrders.FREE_PROVIDER}', '${PaymentOrders.EXEMPT_PROVIDER}')"
            }
        }
        val cursor = orderCursor(before, params).removePrefix(" and ")
        val order = "order by created_at desc, id desc limit :limit"
        if (postgres && scope == OperatorOrderScope.UNFULFILLED) {
            // 걸름(미지급)을 **먼저 굳힌 뒤** 정렬 · 자른다. 소수를 찾는 질의라 정상 상태(미지급 0 줄)에서는 비어 있는데, 계획기는 상태와 지급 시각의 상관을 몰라 약 1,400 줄을 어림하고
            // "정렬된 `idx_payment_orders_created` 를 거꾸로 훑다 보면 `limit` 를 금방 채운다"고 오판해 전체를 읽는다(실측: 12만 줄에서 `Rows Removed by Filter: 120000`). `materialized` 는 걸름이
            // `idx_payment_orders_unfulfilled` 범위로 먼저 평가되게 못 박는다 - 커서는 굳힌 집합 위에서 건다(굳힌 집합이 크면 쪽마다 그 집합을 다시 읽는다: 미지급이 정말 많은 장애 상황의 비용).
            val columns = rows.orderColumnList
            val cursorClause = if (cursor.isEmpty()) "" else " where $cursor"
            return OrderListStatement("with c as materialized (select $columns from payment_orders where ${where.joinToString(" and ")}) select $columns from c$cursorClause $order", params)
        }
        if (cursor.isNotEmpty()) where += cursor
        val clause = if (where.isEmpty()) "" else " where ${where.joinToString(" and ")}"
        return OrderListStatement("${rows.orderSelect}$clause $order", params)
    }

    override fun findOrdersByProviderPayment(providers: Collection<String>, providerPaymentId: String): List<PaymentOrder> {
        if (providers.isEmpty()) return emptyList()
        return jdbc.query(
            "${rows.orderSelect} where provider in (:providers) and provider_payment_id = :payment order by created_at desc, id desc",
            mapOf("providers" to providers, "payment" to providerPaymentId),
        ) { rs, _ -> rows.order(rs) }
    }

    override fun listOrdersByIds(ids: Collection<String>): List<PaymentOrder> {
        if (ids.isEmpty()) return emptyList()
        return jdbc.query("${rows.orderSelect} where id in (:ids) order by created_at desc, id desc", mapOf("ids" to ids)) { rs, _ -> rows.order(rs) }
    }

    override fun listRefundsOfOrders(orderIds: Collection<String>): List<PaymentRefund> {
        if (orderIds.isEmpty()) return emptyList()
        return jdbc.query("${refundRows.refundSelect} where order_id in (:ids) ${refundRows.refundOrder}", mapOf("ids" to orderIds)) { rs, _ -> refundRows.refund(rs) }
    }

    override fun listRefundsByStatus(status: PaymentRefundStatus, before: RefundCursor?, limit: Int): List<PaymentRefund> {
        val params = mutableMapOf<String, Any?>("status" to status.name, "limit" to limit)
        val cursor = if (before == null) "" else " and (requested_at < :at or (requested_at = :at and id < :cursor_id))"
        if (before != null) {
            params["at"] = dialect.instantParam(before.requestedAt)
            params["cursor_id"] = before.id
        }
        return jdbc.query("${refundRows.refundSelect} where status = :status$cursor order by requested_at desc, id desc limit :limit", params) { rs, _ -> refundRows.refund(rs) }
    }

    private fun orderCursor(before: OrderCursor?, params: MutableMap<String, Any?>): String {
        if (before == null) return ""
        params["at"] = dialect.instantParam(before.createdAt)
        params["cursor_id"] = before.id
        return " and (created_at < :at or (created_at = :at and id < :cursor_id))"
    }

    // --- 계정 탈퇴 (벌크). 한 문장 · 멱등 — 이미 지워진 줄은 where 에서 걸러져 두 번째 호출은 아무것도 바꾸지 않는다. ---

    override fun findSubscriptionDiscount(subscriptionId: String): SubscriptionDiscount? = readSubscriptionDiscount(subscriptionId)

    private fun readSubscriptionDiscount(subscriptionId: String, lock: Boolean = false): SubscriptionDiscount? {
        val suffix = if (lock) " for update" else ""
        val head = jdbc.query("${subscriptionDiscountRows.select}$suffix", mapOf("id" to subscriptionId)) { rs, _ -> subscriptionDiscountRows.discount(rs, emptyList()) }.firstOrNull() ?: return null
        val stages = jdbc.query(subscriptionDiscountRows.stageSelect, mapOf("id" to subscriptionId)) { rs, _ -> subscriptionDiscountRows.stage(rs) }
        return head.copy(stages = stages)
    }

    override fun findSubscription(id: String): PaymentSubscription? =
        jdbc.query("${subscriptionRows.select} where id = :id", mapOf("id" to id)) { rs, _ -> subscriptionRows.subscription(rs) }.firstOrNull()

    override fun findSubscriptionByDedupeKey(dedupeKey: String): PaymentSubscription? =
        jdbc.query("${subscriptionRows.select} where dedupe_key = :key", mapOf("key" to dedupeKey)) { rs, _ -> subscriptionRows.subscription(rs) }.firstOrNull()

    override fun findSubscriptionByProviderId(provider: String, providerSubscriptionId: String): PaymentSubscription? =
        jdbc.query("${subscriptionRows.select} where provider = :provider and provider_subscription_id = :id", mapOf("provider" to provider, "id" to providerSubscriptionId)) { rs, _ -> subscriptionRows.subscription(rs) }.firstOrNull()

    override fun findSubscriptionByFirstTransaction(provider: String, transactionId: String): PaymentSubscription? =
        jdbc.query("${subscriptionRows.select} where provider = :provider and first_transaction_id = :id", mapOf("provider" to provider, "id" to transactionId)) { rs, _ -> subscriptionRows.subscription(rs) }.firstOrNull()

    override fun findLiveSubscription(accountId: String): PaymentSubscription? =
        jdbc.query("${subscriptionRows.select} where active_key = :account", mapOf("account" to accountId)) { rs, _ -> subscriptionRows.subscription(rs) }.firstOrNull()

    override fun findLatestSubscription(accountId: String): PaymentSubscription? =
        jdbc.query(
            "${subscriptionRows.select} where account_id = :account and (status <> 'PENDING' or charge_order_id is not null) order by created_at desc, id desc limit 1",
            mapOf("account" to accountId),
        ) { rs, _ -> subscriptionRows.subscription(rs) }.firstOrNull()

    override fun listSubscriptionOrders(subscriptionId: String, limit: Int): List<PaymentOrder> =
        jdbc.query(
            "${rows.orderSelect} where origin_type = 'SUBSCRIPTION' and origin_ref = :id and status <> 'PENDING' order by created_at desc, id desc limit :limit",
            mapOf("id" to subscriptionId, "limit" to limit),
        ) { rs, _ -> rows.order(rs) }

    override fun listSubscriptionIdsOfAccount(accountId: String): List<String> =
        jdbc.queryForList("select id from payment_subscriptions where account_id = :account order by id", mapOf("account" to accountId), String::class.java).filterNotNull()

    override fun listOrderIdsOfAccount(accountId: String): List<String> =
        jdbc.queryForList("select id from payment_orders where account_id = :account order by id", mapOf("account" to accountId), String::class.java).filterNotNull()

    override fun eraseAccountOrders(accountId: String, erasedAt: Instant): Int =
        jdbc.update(
            """update payment_orders set account_erased_at = coalesce(account_erased_at, :at), card_issuer = null, card_last4 = null
               where account_id = :account and (account_erased_at is null or card_issuer is not null or card_last4 is not null)""",
            mapOf("account" to accountId, "at" to dialect.instantParam(erasedAt)),
        )

    override fun replaceAccountReferences(accountId: String, replacement: String): Int =
        // 하나의 트랜잭션(바깥이 있으면 합류) — 이력 · 환불 줄을 먼저, 주문 줄을 마지막에: 대상 주문은 주문 줄이 아직 옛 id 를 가리키는 동안에만 찾을 수 있다
        template.execute {
            var changed = 0
            listOrderIdsOfAccount(accountId).chunked(REPLACE_CHUNK).forEach { ids ->
                changed += jdbc.update(
                    "update payment_events set actor_id = :new where order_id in (:ids) and actor_id = :old",
                    mapOf("ids" to ids, "old" to accountId, "new" to replacement),
                )
                changed += jdbc.update(
                    """update payment_refunds set requested_by = case when requested_by = :old then :new else requested_by end,
                                                 decided_by = case when decided_by = :old then :new else decided_by end
                       where order_id in (:ids) and (requested_by = :old or decided_by = :old)""",
                    mapOf("ids" to ids, "old" to accountId, "new" to replacement),
                )
            }
            // 구독 줄도 같은 계정 id 를 가진다(끝난 줄은 `active_key` 가 NULL — 살아 있는 줄이 남아 있다면 자리도 같이 옮긴다)
            changed += jdbc.update(
                "update payment_subscriptions set account_id = :new, active_key = case when active_key = :old then :new else active_key end where account_id = :old",
                mapOf("old" to accountId, "new" to replacement),
            )
            changed + jdbc.update("update payment_orders set account_id = :new where account_id = :old", mapOf("old" to accountId, "new" to replacement))
        }!!

    private companion object {
        /** `order_id in (…)` 한 문장에 싣는 주문 수 — 탈퇴한 계정의 주문이 아주 많아도 문장이 커지지 않는다. */
        const val REPLACE_CHUNK = 500

        /** 「돈은 받았는데 지급이 안 끝난」 줄이 될 수 있는 상태 — 전액 환불된 줄은 지급을 건너뛰므로 뺀다. */
        val UNFULFILLED_STATUSES = listOf(PaymentOrderStatus.PAID, PaymentOrderStatus.PARTIALLY_REFUNDED)
    }

    private inner class JdbcTx : PaymentLedgerTx {
        /** 이 트랜잭션이 넣었거나 잡은 수신함 줄 — 하나뿐이다. 주문을 잠그기 전에는 잡을 수 있고, 잡은 뒤에는 주문을 잠그지 못한다(락 순서: 주문 → 수신함 줄). */
        private var ownedWebhookId: Long? = null

        /** 이 트랜잭션이 잠갔거나 넣은 주문 — 하나뿐이다. */
        private var ownedOrderId: String? = null

        /** 이 트랜잭션이 잠갔거나 넣은 구독 — 하나뿐이다. 락 순서는 주문 → 구독. */
        private var ownedSubscriptionId: String? = null

        override fun lockOrder(id: String): PaymentOrder? {
            if (id == ownedOrderId) return readLocked(id)
            check(ownedOrderId == null) { "one order per transaction: already holds $ownedOrderId" }
            check(ownedWebhookId == null) { "lock order is order, then webhook row: this transaction already holds a webhook row" }
            // ① 잠그는 문장 — 다른 트랜잭션이 잡고 있으면 그것이 끝날 때까지 기다린다
            val locked = jdbc.queryForList("select id from payment_orders where id = :id for update", mapOf("id" to id), String::class.java)
            if (locked.isEmpty()) return null
            ownedOrderId = id
            // ② 판단하는 읽기 — 새 문장, 잠금 읽기: 기다리는 동안 커밋된 변경까지 반영한 지금의 줄
            return readLocked(id)
        }

        private fun readLocked(id: String): PaymentOrder? =
            jdbc.query("${rows.orderSelect} where id = :id for update", mapOf("id" to id)) { rs, _ -> rows.order(rs) }.firstOrNull()

        override fun findOrderByDedupeKey(dedupeKey: String): PaymentOrder? = this@JdbcPaymentLedgerStore.findOrderByDedupeKey(dedupeKey)

        override fun findOrderByProviderPayment(provider: String, providerPaymentId: String): PaymentOrder? =
            jdbc.query(
                "${rows.orderSelect} where provider = :provider and provider_payment_id = :payment",
                mapOf("provider" to provider, "payment" to providerPaymentId),
            ) { rs, _ -> rows.order(rs) }.firstOrNull()

        override fun insertOrderIfAbsent(order: PaymentOrder): Boolean {
            check(ownedOrderId == null) { "one order per transaction: already holds $ownedOrderId" }
            jdbc.update(insertOrderSql, rows.orderParams(order))
            // 갱신 행 수로는 못 가른다(MySQL) — 방금 넣은 id 가 줄로 있으면 우리가 넣은 것이다(id 는 무작위 128비트)
            val inserted = (jdbc.queryForObject("select count(*) from payment_orders where id = :id", mapOf("id" to order.id), Int::class.java) ?: 0) > 0
            if (inserted) ownedOrderId = order.id
            return inserted
        }

        override fun updateOrder(order: PaymentOrder) {
            check(order.id == ownedOrderId) { "the order must be locked in this transaction before it is updated" }
            try {
                jdbc.update("update payment_orders set ${rows.orderUpdateAssignments} where id = :id", rows.orderParams(order))
            } catch (e: DuplicateKeyException) {
                // 고칠 수 있는 유니크는 (provider, provider_payment_id) 뿐이다. PostgreSQL 은 이 문장 때문에 트랜잭션이 못 쓰게 되므로 호출자가 이 트랜잭션을 끝내야 한다(서비스는 던져서 되돌린다)
                throw PaymentLedgerConflictException("provider payment id is already attached to another order")
            }
        }

        override fun listOrderDiscounts(orderId: String): List<OrderDiscount> =
            jdbc.query("${discountRows.select} where order_id = :order order by seq", mapOf("order" to orderId)) { rs, _ -> discountRows.line(rs) }

        override fun insertOrderDiscount(line: OrderDiscount) {
            check(line.orderId == ownedOrderId) { "the order must be locked in this transaction before a discount line is inserted" }
            jdbc.update(discountRows.insert, discountRows.params(line))
        }

        override fun updateOrderDiscountStatus(orderId: String, seq: Int, status: OrderDiscountStatus, at: Instant) {
            check(orderId == ownedOrderId) { "the order must be locked in this transaction before a discount line is updated" }
            val updated = jdbc.update(
                "update payment_order_discounts set status = :status, updated_at = :at where order_id = :order and seq = :seq",
                mapOf("status" to status.name, "at" to dialect.instantParam(at), "order" to orderId, "seq" to seq),
            )
            check(updated == 1) { "no such discount line" }
        }

        override fun lockRefund(id: String): PaymentRefund? {
            // 락 순서는 주문 → 환불: 어느 주문의 줄인지 잠그지 않고 먼저 읽고, 그 주문이 내 것일 때만 잠금 읽기로 다시 읽는다
            val unlocked = findRefund(id) ?: return null
            check(unlocked.orderId == ownedOrderId) { "the refund's order must be locked in this transaction first (order, then refund)" }
            return jdbc.query("${refundRows.refundSelect} where id = :id for update", mapOf("id" to id)) { rs, _ -> refundRows.refund(rs) }.firstOrNull()
        }

        override fun findRefundByDedupeKey(dedupeKey: String): PaymentRefund? = this@JdbcPaymentLedgerStore.findRefundByDedupeKey(dedupeKey)

        override fun listRefunds(orderId: String): List<PaymentRefund> {
            check(orderId == ownedOrderId) { "the order must be locked in this transaction before its refunds are listed" }
            return jdbc.query("${refundRows.refundSelect} where order_id = :order ${refundRows.refundOrder} for update", mapOf("order" to orderId)) { rs, _ -> refundRows.refund(rs) }
        }

        override fun insertRefundIfAbsent(refund: PaymentRefund): Boolean {
            check(refund.orderId == ownedOrderId) { "the refund's order must be locked in this transaction before a refund is inserted" }
            jdbc.update(insertRefundSql, refundRows.params(refund))
            // 갱신 행 수로는 못 가른다(MySQL) — 방금 넣은 id 가 줄로 있으면 우리가 넣은 것이다(id 는 무작위 128비트)
            return (jdbc.queryForObject("select count(*) from payment_refunds where id = :id", mapOf("id" to refund.id), Int::class.java) ?: 0) > 0
        }

        override fun updateRefund(refund: PaymentRefund) {
            check(refund.orderId == ownedOrderId) { "the refund's order must be locked in this transaction before a refund is updated" }
            val updated = try {
                jdbc.update("update payment_refunds set ${refundRows.refundUpdateAssignments} where id = :id and order_id = :order_id", refundRows.params(refund))
            } catch (e: DuplicateKeyException) {
                // 고칠 수 있는 유니크는 open_key · (order_id, provider_refund_id). PostgreSQL 은 이 문장 때문에 트랜잭션이 못 쓰게 되므로 호출자가 이 트랜잭션을 끝내야 한다(서비스는 던져서 되돌린다)
                throw PaymentLedgerConflictException("the refund collides with another refund of this order")
            }
            check(updated == 1) { "no such refund to update" }
        }

        override fun recordWebhookDelivery(candidate: PaymentWebhookEvent): RecordedWebhookDelivery {
            check(ownedWebhookId == null) { "one webhook row per transaction: already holds $ownedWebhookId" }
            return webhook.record(candidate).also { ownedWebhookId = it.event.id }
        }

        override fun countWebhookEventsOfOrder(orderId: String, resourceType: String, since: java.time.Instant): Int {
            check(orderId == ownedOrderId) { "the order must be locked in this transaction before its webhook rows are counted" }
            return webhook.countOfOrderSince(orderId, resourceType, since)
        }

        override fun lockWebhookEvent(id: Long): PaymentWebhookEvent? {
            if (id != ownedWebhookId) {
                check(ownedWebhookId == null) { "one webhook row per transaction: already holds $ownedWebhookId" }
                if (!webhook.lock(id)) return null
                ownedWebhookId = id
            }
            return webhook.readLocked(id)
        }

        override fun updateWebhookEvent(event: PaymentWebhookEvent) {
            check(event.id == ownedWebhookId) { "the webhook row must be recorded or locked in this transaction before it is updated" }
            webhook.update(event)
        }

        override fun lockSubscription(id: String): PaymentSubscription? {
            if (id == ownedSubscriptionId) return readLockedSubscription(id)
            check(ownedSubscriptionId == null) { "one subscription per transaction: already holds $ownedSubscriptionId" }
            val locked = jdbc.queryForList("select id from payment_subscriptions where id = :id for update", mapOf("id" to id), String::class.java)
            if (locked.isEmpty()) return null
            ownedSubscriptionId = id
            return readLockedSubscription(id)
        }

        private fun readLockedSubscription(id: String): PaymentSubscription? =
            jdbc.query("${subscriptionRows.select} where id = :id for update", mapOf("id" to id)) { rs, _ -> subscriptionRows.subscription(rs) }.firstOrNull()

        override fun subscriptionDiscount(subscriptionId: String): SubscriptionDiscount? = readSubscriptionDiscount(subscriptionId, lock = subscriptionId == ownedSubscriptionId)

        override fun insertSubscriptionDiscount(discount: SubscriptionDiscount) {
            check(discount.subscriptionId == ownedSubscriptionId) { "the subscription must be locked in this transaction before its discount is inserted" }
            jdbc.update(subscriptionDiscountRows.insert, subscriptionDiscountRows.params(discount))
            discount.stages.forEachIndexed { seq, stage -> jdbc.update(subscriptionDiscountRows.stageInsert, subscriptionDiscountRows.stageParams(discount.subscriptionId, seq, stage)) }
        }

        override fun updateSubscriptionDiscount(discount: SubscriptionDiscount) {
            check(discount.subscriptionId == ownedSubscriptionId) { "the subscription must be locked in this transaction before its discount is updated" }
            val updated = jdbc.update(
                "update payment_subscription_discounts set periods_used = :periods_used, status = :status, updated_at = :updated_at where subscription_id = :subscription_id",
                subscriptionDiscountRows.params(discount),
            )
            check(updated == 1) { "no such subscription discount" }
        }

        override fun insertSubscriptionIfAbsent(subscription: PaymentSubscription): Boolean {
            check(ownedSubscriptionId == null) { "one subscription per transaction: already holds ${ownedSubscriptionId}" }
            jdbc.update(insertSubscriptionSql, subscriptionRows.params(subscription))
            // 갱신 행 수로는 못 가른다(MySQL) · 같은 요청 키의 다른 줄이 이겼으면 이 id 의 줄은 없다 — 이 id 의 줄이 있고 고객 키(무작위 128비트)가 우리 것이면 우리가 넣었다
            val owner = jdbc.queryForList("select customer_key from payment_subscriptions where id = :id", mapOf("id" to subscription.id), String::class.java).firstOrNull()
            val inserted = owner != null && owner == subscription.customerKey
            if (inserted) ownedSubscriptionId = subscription.id
            return inserted
        }

        override fun updateSubscription(subscription: PaymentSubscription) {
            check(subscription.id == ownedSubscriptionId) { "the subscription must be locked in this transaction before it is updated" }
            try {
                jdbc.update("update payment_subscriptions set ${subscriptionRows.updateAssignments} where id = :id", subscriptionRows.params(subscription))
            } catch (e: DuplicateKeyException) {
                // 겹칠 수 있는 유니크는 active_key(계정당 살아 있는 구독 하나)와 결제사 구독 id(하나는 우리 구독 하나에만)다. PostgreSQL 은 이 문장 때문에 트랜잭션이 못 쓰게 되므로 호출자가 이 트랜잭션을 끝내야 한다(서비스는 던져서 되돌린다)
                throw PaymentLedgerConflictException("the account already has a live subscription")
            }
        }

        override fun appendEvent(event: PaymentEvent) {
            jdbc.update(
                """insert into payment_events (order_id, refund_id, type, from_status, to_status, actor, actor_id, provider_code, provider_trace_id, trace_id, created_at)
                   values (:order_id, :refund_id, :type, :from_status, :to_status, :actor, :actor_id, :provider_code, :provider_trace_id, :trace_id, :created_at)""",
                rows.eventParams(event),
            )
        }
    }
}
