package dev.sumin.skeleton.payment.jdbc

import dev.sumin.skeleton.payment.PaymentWebhookEvent
import dev.sumin.skeleton.payment.PaymentWebhookStatus
import dev.sumin.skeleton.payment.RecordedWebhookDelivery
import dev.sumin.skeleton.payment.WebhookEventCursor
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.time.Instant
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * 수신함 표(`payment_webhook_events`)의 문장들 — [JdbcPaymentLedgerStore] 가 부른다. 한 트랜잭션에 줄 하나라는 규칙과 쓰기 전에 잡았는지의 검사는 부르는 쪽(트랜잭션)이 한다.
 * 규칙은 주문 표와 같다: 유니크는 DB 가 가른다(충돌하면 아무것도 하지 않는 insert — PostgreSQL 은 유니크 위반 예외 뒤 트랜잭션을 못 쓴다), 넣었는지는 행 수가 아니라 센 값으로 가른다(MySQL 은 이미 있는 행에도 1 을 돌려준다),
 * 판단하는 읽기는 잠금 읽기다.
 */
internal class JdbcWebhookInbox(private val jdbc: NamedParameterJdbcTemplate, private val dialect: SqlDialect) {
    private val rows = PaymentWebhookRows(dialect)

    private val insertSql: String = rows.insertColumns.let { columns ->
        if (dialect.vendor == "postgresql") {
            // 이미 있는 (provider, event_key) 면 아무것도 하지 않는다 — 앞선 트랜잭션이 끝날 때까지 기다린 뒤 판단한다
            "insert into payment_webhook_events (${columns.joinToString(", ")}) values (${columns.joinToString(", ") { ":$it" }}) on conflict (provider, event_key) do nothing"
        } else {
            dialect.insertIgnore("payment_webhook_events", columns, listOf("provider"))
        }
    }

    fun find(id: Long): PaymentWebhookEvent? =
        jdbc.query("${rows.select} where id = :id", mapOf("id" to id)) { rs, _ -> rows.event(rs) }.firstOrNull()

    fun findByKey(provider: String, eventKey: String): PaymentWebhookEvent? =
        jdbc.query("${rows.select} where provider = :provider and event_key = :key", mapOf("provider" to provider, "key" to eventKey)) { rs, _ -> rows.event(rs) }.firstOrNull()

    /**
     * 그 주문의 줄 가운데 창 안의 것 — `(order_id, received_at)` 인덱스(`idx_payment_webhook_events_order`)로 그 주문의 줄만 훑는다. 주문 줄 락 안에서 부른다(부르는 쪽 검사):
     * 새 줄은 모두 주문 락 뒤에 들어가므로 세는 사이에 늘지 않는다. 표식 줄은 `resource_type` 이 달라 세지 않는다.
     */
    fun countOfOrderSince(orderId: String, resourceType: String, since: Instant): Int =
        jdbc.queryForObject(
            "select count(*) from payment_webhook_events where order_id = :order and resource_type = :type and received_at >= :since",
            mapOf("order" to orderId, "type" to resourceType, "since" to dialect.instantParam(since)),
            Int::class.java,
        ) ?: 0

    /**
     * 상태를 정하지 않은 목록은 **문장 하나**, `id`(PK) 내림차순 — 커서는 id 다. 상태마다 훑어 합치면 문장마다 스냅샷이 달라 줄이 두 번 나오거나 빠질 수 있다.
     * 상태 하나는 `(status, received_at, id)` 인덱스를 거꾸로 한 번 훑는다. 커서 조건은 행 값 비교 대신 풀어 쓴다(MySQL 이 범위 접근을 쓰도록).
     */
    fun list(status: PaymentWebhookStatus?, before: WebhookEventCursor?, limit: Int): List<PaymentWebhookEvent> {
        val params = mutableMapOf<String, Any?>("limit" to limit)
        if (status == null) {
            val cursor = if (before == null) "" else " where id < :cursor_id"
            if (before != null) params["cursor_id"] = before.id
            return jdbc.query("${rows.select}$cursor order by id desc limit :limit", params) { rs, _ -> rows.event(rs) }
        }
        params["status"] = status.name
        val cursor = if (before == null) "" else " and (received_at < :at or (received_at = :at and id < :cursor_id))"
        if (before != null) {
            params["at"] = dialect.instantParam(before.receivedAt)
            params["cursor_id"] = before.id
        }
        return jdbc.query("${rows.select} where status = :status$cursor ${rows.newestFirst} limit :limit", params) { rs, _ -> rows.event(rs) }
    }

    /** ① 충돌하면 아무것도 하지 않는 insert(deliveries 0) → ② 문장 하나로 올린다(줄 잠금) → ③ 센 뒤의 줄을 읽는다. 넣었는지는 센 값(1)으로 가른다. */
    fun record(candidate: PaymentWebhookEvent): RecordedWebhookDelivery {
        jdbc.update(insertSql, rows.params(candidate.copy(deliveries = 0)))
        val key = mapOf("provider" to candidate.provider, "key" to candidate.eventKey)
        jdbc.update("update payment_webhook_events set deliveries = deliveries + 1 where provider = :provider and event_key = :key", key)
        val row = jdbc.query("${rows.select} where provider = :provider and event_key = :key for update", key) { rs, _ -> rows.event(rs) }.single()
        return RecordedWebhookDelivery(row, first = row.deliveries == 1)
    }

    /** 잠그는 문장 — 다른 트랜잭션이 잡고 있으면 끝날 때까지 기다린다. 줄이 있으면 true. */
    fun lock(id: Long): Boolean = jdbc.queryForList("select id from payment_webhook_events where id = :id for update", mapOf("id" to id), Long::class.java).isNotEmpty()

    /** 판단하는 읽기는 새 문장 · 잠금 읽기 — 기다리는 동안 커밋된 변경까지 반영한 지금의 줄. */
    fun readLocked(id: Long): PaymentWebhookEvent? =
        jdbc.query("${rows.select} where id = :id for update", mapOf("id" to id)) { rs, _ -> rows.event(rs) }.firstOrNull()

    fun update(event: PaymentWebhookEvent) {
        val updated = jdbc.update("update payment_webhook_events set ${rows.updateAssignments} where id = :id", rows.params(event))
        check(updated == 1) { "no such webhook row to update" }
    }
}
