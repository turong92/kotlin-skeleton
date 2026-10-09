package dev.sumin.skeleton.payment.jdbc

import dev.sumin.skeleton.payment.PaymentWebhookEvent
import dev.sumin.skeleton.payment.PaymentWebhookStatus
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.sql.ResultSet
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource

/** 수신함 줄 ↔ SQL 의 대응 한 곳 ([PaymentOrderRows] 와 같은 방식). `id` 는 DB 가 정한다 — 넣을 때 열 목록에 없다. */
internal class PaymentWebhookRows(private val dialect: SqlDialect) {
    val select: String = "select ${COLUMNS.joinToString(", ")} from payment_webhook_events"

    val insertColumns: List<String> = COLUMNS.filter { it != "id" }

    /** 수정하는 칸은 상태 · 사유 · 처리 시각뿐이다 — 나머지는 첫 전달의 값이다(`deliveries` 는 전달을 셀 때 문장 하나로만 올린다). */
    val updateAssignments: String = "status = :status, last_error = :last_error, processed_at = :processed_at"

    /** 최신순 — 받은 시각, 같으면 id 가 큰 쪽이 먼저. `(status, received_at, id)` 인덱스를 거꾸로 훑는다. */
    val newestFirst: String = "order by received_at desc, id desc"

    fun event(rs: ResultSet): PaymentWebhookEvent = PaymentWebhookEvent(
        id = rs.getLong("id"),
        provider = rs.getString("provider"),
        eventKey = rs.getString("event_key"),
        eventType = rs.getString("event_type"),
        resourceType = rs.getString("resource_type"),
        resourceId = rs.getString("resource_id"),
        orderId = rs.getString("order_id"),
        payload = rs.getString("payload"),
        payloadHash = rs.getString("payload_hash"),
        status = PaymentWebhookStatus.valueOf(rs.getString("status")),
        deliveries = rs.getInt("deliveries"),
        lastError = rs.getString("last_error"),
        receivedAt = dialect.readInstant(rs, "received_at")!!,
        processedAt = dialect.readInstant(rs, "processed_at"),
    )

    fun params(event: PaymentWebhookEvent): MapSqlParameterSource = MapSqlParameterSource()
        .addValue("id", event.id)
        .addValue("provider", event.provider)
        .addValue("event_key", event.eventKey)
        .addValue("event_type", event.eventType)
        .addValue("resource_type", event.resourceType)
        .addValue("resource_id", event.resourceId)
        .addValue("order_id", event.orderId)
        .addValue("payload", event.payload)
        .addValue("payload_hash", event.payloadHash)
        .addValue("status", event.status.name)
        .addValue("deliveries", event.deliveries)
        .addValue("last_error", event.lastError)
        .addValue("received_at", dialect.instantParam(event.receivedAt))
        .addValue("processed_at", dialect.instantParam(event.processedAt))

    companion object {
        val COLUMNS = listOf(
            "id", "provider", "event_key", "event_type", "resource_type", "resource_id", "order_id", "payload", "payload_hash", "status", "deliveries", "last_error", "received_at", "processed_at",
        )
    }
}
