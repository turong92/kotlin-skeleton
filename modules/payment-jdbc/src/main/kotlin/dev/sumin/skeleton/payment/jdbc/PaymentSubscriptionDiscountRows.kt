package dev.sumin.skeleton.payment.jdbc

import dev.sumin.skeleton.common.discount.DiscountStage
import dev.sumin.skeleton.common.discount.DiscountStageType
import dev.sumin.skeleton.payment.SubscriptionDiscount
import dev.sumin.skeleton.payment.SubscriptionDiscountStatus
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.sql.ResultSet
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource

/** 구독의 할인 일정(`payment_subscription_discounts` + 단계 복사본 `…_stages`) ↔ SQL. 단계는 시작 때 한 번 넣고 고치지 않는다. */
internal class PaymentSubscriptionDiscountRows(private val dialect: SqlDialect) {
    val select: String = "select subscription_id, kind, source_ref, label, periods_used, status, created_at, updated_at from payment_subscription_discounts where subscription_id = :id"

    val stageSelect: String = "select type, value, max_discount, periods from payment_subscription_discount_stages where subscription_id = :id order by seq"

    val insert: String =
        "insert into payment_subscription_discounts (subscription_id, kind, source_ref, label, periods_used, status, created_at, updated_at) " +
            "values (:subscription_id, :kind, :source_ref, :label, :periods_used, :status, :created_at, :updated_at)"

    val stageInsert: String =
        "insert into payment_subscription_discount_stages (subscription_id, seq, type, value, max_discount, periods) values (:subscription_id, :seq, :type, :value, :max_discount, :periods)"

    fun discount(rs: ResultSet, stages: List<DiscountStage>) = SubscriptionDiscount(
        subscriptionId = rs.getString("subscription_id"),
        kind = rs.getString("kind"),
        sourceRef = rs.getString("source_ref"),
        label = rs.getString("label"),
        stages = stages,
        periodsUsed = rs.getInt("periods_used"),
        status = SubscriptionDiscountStatus.valueOf(rs.getString("status")),
        createdAt = dialect.readInstant(rs, "created_at")!!,
        updatedAt = dialect.readInstant(rs, "updated_at")!!,
    )

    fun stage(rs: ResultSet) = DiscountStage(
        type = DiscountStageType.valueOf(rs.getString("type")),
        value = rs.getLong("value"),
        maxDiscount = rs.getLong("max_discount").takeUnless { rs.wasNull() },
        periods = rs.getInt("periods").takeUnless { rs.wasNull() },
    )

    fun params(discount: SubscriptionDiscount): MapSqlParameterSource = MapSqlParameterSource()
        .addValue("subscription_id", discount.subscriptionId)
        .addValue("kind", discount.kind)
        .addValue("source_ref", discount.sourceRef)
        .addValue("label", discount.label)
        .addValue("periods_used", discount.periodsUsed)
        .addValue("status", discount.status.name)
        .addValue("created_at", dialect.instantParam(discount.createdAt))
        .addValue("updated_at", dialect.instantParam(discount.updatedAt))

    fun stageParams(subscriptionId: String, seq: Int, stage: DiscountStage): MapSqlParameterSource = MapSqlParameterSource()
        .addValue("subscription_id", subscriptionId)
        .addValue("seq", seq)
        .addValue("type", stage.type.name)
        .addValue("value", stage.value)
        .addValue("max_discount", stage.maxDiscount)
        .addValue("periods", stage.periods)
}
