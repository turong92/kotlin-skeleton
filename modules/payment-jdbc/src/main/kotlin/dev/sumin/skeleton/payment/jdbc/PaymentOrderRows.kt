package dev.sumin.skeleton.payment.jdbc

import dev.sumin.skeleton.payment.PaymentActor
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentEvent
import dev.sumin.skeleton.payment.PaymentEventType
import dev.sumin.skeleton.payment.PaymentMode
import dev.sumin.skeleton.payment.PaymentOrder
import dev.sumin.skeleton.payment.PaymentOrderKind
import dev.sumin.skeleton.payment.PaymentOrderStatus
import dev.sumin.skeleton.payment.PaymentOriginType
import dev.sumin.skeleton.payment.PaymentReviewReason
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.sql.ResultSet
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource

/**
 * 주문 줄 · 이력 줄 ↔ SQL 의 대응 한 곳 — 칸 이름이 SELECT · INSERT · UPDATE 에 흩어져 어긋나지 않게 목록 하나에서 만든다.
 * 시각은 [SqlDialect] 로만 바인딩 · 읽는다(PG `timestamptz` / MySQL `datetime(6)` UTC).
 */
internal class PaymentOrderRows(private val dialect: SqlDialect) {
    val orderColumnList: String = ORDER_COLUMNS.joinToString(", ")

    val orderSelect: String = "select $orderColumnList from payment_orders"

    /** `insert` 의 열 목록 · 값 목록 — 충돌 처리 문구는 부르는 쪽이 붙인다. */
    val orderInsertColumns: List<String> = ORDER_COLUMNS

    /** `update … set` 의 대입 목록 — 주문 줄 전체를 바꾼다(`id` 만 빼고). */
    val orderUpdateAssignments: String = ORDER_COLUMNS.filter { it != "id" }.joinToString(", ") { "$it = :$it" }

    fun order(rs: ResultSet): PaymentOrder = PaymentOrder(
        id = rs.getString("id"),
        accountId = rs.getString("account_id"),
        kind = PaymentOrderKind.valueOf(rs.getString("kind")),
        status = PaymentOrderStatus.valueOf(rs.getString("status")),
        provider = rs.getString("provider"),
        providerMode = PaymentMode.valueOf(rs.getString("provider_mode")),
        providerPaymentId = rs.getString("provider_payment_id"),
        amount = PaymentAmount(rs.getLong("amount"), rs.getString("currency")),
        paidAmount = rs.longOrNull("paid_amount"),
        taxAmount = rs.longOrNull("tax_amount"),
        refundedAmount = rs.getLong("refunded_amount"),
        itemType = rs.getString("item_type"),
        itemRef = rs.getString("item_ref"),
        quantity = rs.getInt("quantity"),
        orderName = rs.getString("order_name"),
        originType = PaymentOriginType.valueOf(rs.getString("origin_type")),
        originRef = rs.getString("origin_ref"),
        dedupeKey = rs.getString("dedupe_key"),
        method = rs.getString("method"),
        cardIssuer = rs.getString("card_issuer"),
        cardLast4 = rs.getString("card_last4"),
        failureCode = rs.getString("failure_code"),
        reviewReason = rs.getString("review_reason")?.let { PaymentReviewReason.valueOf(it) },
        checkoutExpiresAt = dialect.readInstant(rs, "checkout_expires_at")!!,
        confirmStartedAt = dialect.readInstant(rs, "confirm_started_at"),
        paidAt = dialect.readInstant(rs, "paid_at"),
        fulfilledAt = dialect.readInstant(rs, "fulfilled_at"),
        closedAt = dialect.readInstant(rs, "closed_at"),
        accountErasedAt = dialect.readInstant(rs, "account_erased_at"),
        createdAt = dialect.readInstant(rs, "created_at")!!,
        updatedAt = dialect.readInstant(rs, "updated_at")!!,
        country = rs.getString("country")?.trim(),
    )

    /** 통화는 정규화한 값(대문자 · 공백 없음)으로 저장한다 — 읽을 때도 그 값이다. 비교는 `PaymentAmount.sameAs` 로 한다. */
    fun orderParams(order: PaymentOrder): MapSqlParameterSource = MapSqlParameterSource()
        .addValue("id", order.id)
        .addValue("account_id", order.accountId)
        .addValue("kind", order.kind.name)
        .addValue("status", order.status.name)
        .addValue("provider", order.provider)
        .addValue("provider_mode", order.providerMode.name)
        .addValue("provider_payment_id", order.providerPaymentId)
        .addValue("currency", order.amount.normalizedCurrency)
        .addValue("amount", order.amount.amount)
        .addValue("paid_amount", order.paidAmount)
        .addValue("tax_amount", order.taxAmount)
        .addValue("refunded_amount", order.refundedAmount)
        .addValue("item_type", order.itemType)
        .addValue("item_ref", order.itemRef)
        .addValue("quantity", order.quantity)
        .addValue("order_name", order.orderName)
        .addValue("origin_type", order.originType.name)
        .addValue("origin_ref", order.originRef)
        .addValue("dedupe_key", order.dedupeKey)
        .addValue("method", order.method)
        .addValue("card_issuer", order.cardIssuer)
        .addValue("card_last4", order.cardLast4)
        .addValue("failure_code", order.failureCode)
        .addValue("review_reason", order.reviewReason?.name)
        .addValue("checkout_expires_at", dialect.instantParam(order.checkoutExpiresAt))
        .addValue("confirm_started_at", dialect.instantParam(order.confirmStartedAt))
        .addValue("paid_at", dialect.instantParam(order.paidAt))
        .addValue("fulfilled_at", dialect.instantParam(order.fulfilledAt))
        .addValue("closed_at", dialect.instantParam(order.closedAt))
        .addValue("account_erased_at", dialect.instantParam(order.accountErasedAt))
        .addValue("created_at", dialect.instantParam(order.createdAt))
        .addValue("updated_at", dialect.instantParam(order.updatedAt))
        .addValue("country", order.country)

    val eventSelect: String =
        "select order_id, refund_id, type, from_status, to_status, actor, actor_id, provider_code, provider_trace_id, trace_id, created_at from payment_events"

    fun event(rs: ResultSet): PaymentEvent = PaymentEvent(
        orderId = rs.getString("order_id"),
        type = PaymentEventType.valueOf(rs.getString("type")),
        actor = PaymentActor.valueOf(rs.getString("actor")),
        createdAt = dialect.readInstant(rs, "created_at")!!,
        refundId = rs.getString("refund_id"),
        fromStatus = rs.getString("from_status"),
        toStatus = rs.getString("to_status"),
        actorId = rs.getString("actor_id"),
        providerCode = rs.getString("provider_code"),
        providerTraceId = rs.getString("provider_trace_id"),
        traceId = rs.getString("trace_id"),
    )

    fun eventParams(event: PaymentEvent): MapSqlParameterSource = MapSqlParameterSource()
        .addValue("order_id", event.orderId)
        .addValue("refund_id", event.refundId)
        .addValue("type", event.type.name)
        .addValue("from_status", event.fromStatus)
        .addValue("to_status", event.toStatus)
        .addValue("actor", event.actor.name)
        .addValue("actor_id", event.actorId)
        .addValue("provider_code", event.providerCode)
        .addValue("provider_trace_id", event.providerTraceId)
        .addValue("trace_id", event.traceId)
        .addValue("created_at", dialect.instantParam(event.createdAt))

    private fun ResultSet.longOrNull(column: String): Long? = getLong(column).takeUnless { wasNull() }

    companion object {
        val ORDER_COLUMNS = listOf(
            "id", "account_id", "kind", "status", "provider", "provider_mode", "provider_payment_id", "currency", "amount", "paid_amount", "tax_amount",
            "refunded_amount", "item_type", "item_ref", "quantity", "order_name", "origin_type", "origin_ref", "dedupe_key", "method", "card_issuer", "card_last4",
            "failure_code", "review_reason", "checkout_expires_at", "confirm_started_at", "paid_at", "fulfilled_at", "closed_at", "account_erased_at", "created_at", "updated_at", "country",
        )
    }
}
