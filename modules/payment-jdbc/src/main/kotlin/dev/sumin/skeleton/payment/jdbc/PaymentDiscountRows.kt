package dev.sumin.skeleton.payment.jdbc

import dev.sumin.skeleton.payment.OrderDiscount
import dev.sumin.skeleton.payment.OrderDiscountStatus
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.sql.ResultSet
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource

/** 주문의 할인 내역 줄(`payment_order_discounts`) ↔ SQL. 시각은 [SqlDialect] 로만 바인딩 · 읽는다. */
internal class PaymentDiscountRows(private val dialect: SqlDialect) {
    val select: String = "select order_id, seq, kind, source_ref, label, amount, currency, status, created_at, updated_at from payment_order_discounts"

    val insert: String =
        "insert into payment_order_discounts (order_id, seq, kind, source_ref, label, amount, currency, status, created_at, updated_at) " +
            "values (:order_id, :seq, :kind, :source_ref, :label, :amount, :currency, :status, :created_at, :updated_at)"

    fun line(rs: ResultSet) = OrderDiscount(
        orderId = rs.getString("order_id"),
        seq = rs.getInt("seq"),
        kind = rs.getString("kind"),
        sourceRef = rs.getString("source_ref"),
        label = rs.getString("label"),
        amount = rs.getLong("amount"),
        currency = rs.getString("currency").trim(),
        status = OrderDiscountStatus.valueOf(rs.getString("status")),
        createdAt = dialect.readInstant(rs, "created_at")!!,
        updatedAt = dialect.readInstant(rs, "updated_at")!!,
    )

    fun params(line: OrderDiscount): MapSqlParameterSource = MapSqlParameterSource()
        .addValue("order_id", line.orderId)
        .addValue("seq", line.seq)
        .addValue("kind", line.kind)
        .addValue("source_ref", line.sourceRef)
        .addValue("label", line.label)
        .addValue("amount", line.amount)
        .addValue("currency", line.currency)
        .addValue("status", line.status.name)
        .addValue("created_at", dialect.instantParam(line.createdAt))
        .addValue("updated_at", dialect.instantParam(line.updatedAt))
}
