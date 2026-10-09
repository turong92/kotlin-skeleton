package dev.sumin.skeleton.payment.jdbc

import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentMode
import dev.sumin.skeleton.payment.PaymentSubscription
import dev.sumin.skeleton.payment.SubscriptionEndReason
import dev.sumin.skeleton.payment.SubscriptionStatus
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.sql.ResultSet
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource

/** 구독 줄 ↔ SQL 의 대응 한 곳 — [PaymentOrderRows] 와 같은 방식(칸 이름 목록 하나에서 SELECT · INSERT · UPDATE 를 만든다). */
internal class PaymentSubscriptionRows(private val dialect: SqlDialect) {
    val select: String = "select ${COLUMNS.joinToString(", ")} from payment_subscriptions"

    val insertColumns: List<String> = COLUMNS

    val updateAssignments: String = COLUMNS.filter { it != "id" }.joinToString(", ") { "$it = :$it" }

    fun subscription(rs: ResultSet): PaymentSubscription = PaymentSubscription(
        id = rs.getString("id"),
        accountId = rs.getString("account_id"),
        provider = rs.getString("provider"),
        providerMode = PaymentMode.valueOf(rs.getString("provider_mode")),
        status = SubscriptionStatus.valueOf(rs.getString("status")),
        planCode = rs.getString("plan_code"),
        amount = PaymentAmount(rs.getLong("amount"), rs.getString("currency")),
        period = rs.getString("period"),
        anchorAt = dialect.readInstant(rs, "anchor_at"),
        periodSeq = rs.getInt("period_seq"),
        currentPeriodStart = dialect.readInstant(rs, "current_period_start"),
        currentPeriodEnd = dialect.readInstant(rs, "current_period_end"),
        chargeOrderId = rs.getString("charge_order_id"),
        attempt = rs.getInt("attempt"),
        customerKey = rs.getString("customer_key"),
        billingToken = rs.getString("billing_token"),
        cardIssuer = rs.getString("card_issuer"),
        cardLast4 = rs.getString("card_last4"),
        activeKey = rs.getString("active_key"),
        pendingPlanCode = rs.getString("pending_plan_code"),
        pendingAmount = rs.getLong("pending_amount").takeUnless { rs.wasNull() },
        endReason = rs.getString("end_reason")?.let { SubscriptionEndReason.valueOf(it) },
        endedAt = dialect.readInstant(rs, "ended_at"),
        accountErasedAt = dialect.readInstant(rs, "account_erased_at"),
        createdAt = dialect.readInstant(rs, "created_at")!!,
        updatedAt = dialect.readInstant(rs, "updated_at")!!,
    )

    fun params(s: PaymentSubscription): MapSqlParameterSource = MapSqlParameterSource()
        .addValue("id", s.id)
        .addValue("account_id", s.accountId)
        .addValue("provider", s.provider)
        .addValue("provider_mode", s.providerMode.name)
        .addValue("status", s.status.name)
        .addValue("plan_code", s.planCode)
        .addValue("currency", s.amount.normalizedCurrency)
        .addValue("amount", s.amount.amount)
        .addValue("period", s.period)
        .addValue("anchor_at", dialect.instantParam(s.anchorAt))
        .addValue("period_seq", s.periodSeq)
        .addValue("current_period_start", dialect.instantParam(s.currentPeriodStart))
        .addValue("current_period_end", dialect.instantParam(s.currentPeriodEnd))
        .addValue("charge_order_id", s.chargeOrderId)
        .addValue("attempt", s.attempt)
        .addValue("customer_key", s.customerKey)
        .addValue("billing_token", s.billingToken)
        .addValue("card_issuer", s.cardIssuer)
        .addValue("card_last4", s.cardLast4)
        .addValue("active_key", s.activeKey)
        .addValue("pending_plan_code", s.pendingPlanCode)
        .addValue("pending_amount", s.pendingAmount)
        .addValue("end_reason", s.endReason?.name)
        .addValue("ended_at", dialect.instantParam(s.endedAt))
        .addValue("account_erased_at", dialect.instantParam(s.accountErasedAt))
        .addValue("created_at", dialect.instantParam(s.createdAt))
        .addValue("updated_at", dialect.instantParam(s.updatedAt))

    companion object {
        val COLUMNS = listOf(
            "id", "account_id", "provider", "provider_mode", "status", "plan_code", "currency", "amount", "period", "anchor_at", "period_seq", "current_period_start",
            "current_period_end", "charge_order_id", "attempt", "customer_key", "billing_token", "card_issuer", "card_last4", "active_key", "pending_plan_code",
            "pending_amount", "end_reason", "ended_at", "account_erased_at", "created_at", "updated_at",
        )
    }
}
