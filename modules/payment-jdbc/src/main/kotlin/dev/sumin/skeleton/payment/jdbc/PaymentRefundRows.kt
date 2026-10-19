package dev.sumin.skeleton.payment.jdbc

import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentRefund
import dev.sumin.skeleton.payment.PaymentRefundOrigin
import dev.sumin.skeleton.payment.PaymentRefundStatus
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.sql.ResultSet
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource

/** 환불 줄 ↔ SQL 의 대응 한 곳 ([PaymentOrderRows] 와 같은 방식 — 칸 이름이 문장마다 흩어져 어긋나지 않게 목록 하나에서 만든다). */
internal class PaymentRefundRows(private val dialect: SqlDialect) {
    val refundSelect: String = "select ${COLUMNS.joinToString(", ")} from payment_refunds"

    val refundInsertColumns: List<String> = COLUMNS

    /** `update … set` 의 대입 목록 — 줄 전체를 바꾼다(`id` · `order_id` 만 빼고). */
    val refundUpdateAssignments: String = COLUMNS.filter { it != "id" && it != "order_id" }.joinToString(", ") { "$it = :$it" }

    /** 오래된 순. */
    val refundOrder: String = "order by created_at, id"

    fun refund(rs: ResultSet): PaymentRefund = PaymentRefund(
        id = rs.getString("id"),
        orderId = rs.getString("order_id"),
        status = PaymentRefundStatus.valueOf(rs.getString("status")),
        origin = PaymentRefundOrigin.valueOf(rs.getString("origin")),
        amount = PaymentAmount(rs.getLong("amount"), rs.getString("currency")),
        reasonCode = rs.getString("reason_code"),
        openKey = rs.getString("open_key"),
        dedupeKey = rs.getString("dedupe_key"),
        providerRefundId = rs.getString("provider_refund_id"),
        expectedRemaining = rs.getLong("expected_remaining").takeUnless { rs.wasNull() },
        requestedBy = rs.getString("requested_by"),
        decidedBy = rs.getString("decided_by"),
        failureCode = rs.getString("failure_code"),
        requestedAt = dialect.readInstant(rs, "requested_at")!!,
        sentAt = dialect.readInstant(rs, "sent_at"),
        completedAt = dialect.readInstant(rs, "completed_at"),
        appliedAt = dialect.readInstant(rs, "applied_at"),
        createdAt = dialect.readInstant(rs, "created_at")!!,
        updatedAt = dialect.readInstant(rs, "updated_at")!!,
        requestedAmount = rs.getLong("requested_amount").takeUnless { rs.wasNull() },
        keepSubscription = rs.getBoolean("keep_subscription"),
    )

    /** 통화는 정규화한 값으로 저장한다 — 비교는 `PaymentAmount.sameAs` 로 한다. */
    fun params(refund: PaymentRefund): MapSqlParameterSource = MapSqlParameterSource()
        .addValue("id", refund.id)
        .addValue("order_id", refund.orderId)
        .addValue("status", refund.status.name)
        .addValue("origin", refund.origin.name)
        .addValue("amount", refund.amount.amount)
        .addValue("currency", refund.amount.normalizedCurrency)
        .addValue("reason_code", refund.reasonCode)
        .addValue("open_key", refund.openKey)
        .addValue("dedupe_key", refund.dedupeKey)
        .addValue("provider_refund_id", refund.providerRefundId)
        .addValue("expected_remaining", refund.expectedRemaining)
        .addValue("requested_by", refund.requestedBy)
        .addValue("decided_by", refund.decidedBy)
        .addValue("failure_code", refund.failureCode)
        .addValue("requested_at", dialect.instantParam(refund.requestedAt))
        .addValue("sent_at", dialect.instantParam(refund.sentAt))
        .addValue("completed_at", dialect.instantParam(refund.completedAt))
        .addValue("applied_at", dialect.instantParam(refund.appliedAt))
        .addValue("created_at", dialect.instantParam(refund.createdAt))
        .addValue("updated_at", dialect.instantParam(refund.updatedAt))
        .addValue("requested_amount", refund.requestedAmount)
        .addValue("keep_subscription", refund.keepSubscription)

    companion object {
        val COLUMNS = listOf(
            "id", "order_id", "status", "origin", "amount", "currency", "reason_code", "open_key", "dedupe_key", "provider_refund_id", "expected_remaining",
            "requested_by", "decided_by", "failure_code", "requested_at", "sent_at", "completed_at", "applied_at", "created_at", "updated_at", "requested_amount", "keep_subscription",
        )
    }
}
