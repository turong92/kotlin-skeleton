package dev.sumin.skeleton.app.sample.cheer

import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.time.Instant
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

enum class CheerStatus { PAID, REFUNDED }

/** 응원 기록 한 줄 — 결제 장부의 주문 하나에 한 줄(`order_id` 가 키). */
data class Cheer(
    val orderId: String,
    val accountId: String,
    val amount: Long,
    val currency: String,
    val refundedAmount: Long,
    val status: CheerStatus,
    val createdAt: Instant,
    val refundedAt: Instant?,
)

/** 목록 커서의 안쪽 — 이 줄 **다음**부터. */
data class CheerCursor(val createdAt: Instant, val orderId: String)

/**
 * 응원 기록 표(`cheers`)의 읽기 · 쓰기. 결제 장부의 리스너가 **주문 줄 락 안의 트랜잭션**에서 부르므로 쓰기는 한 문장씩이다(짧게). 스프링의 트랜잭션에 합류하는 `JdbcClient` 라서
 * 장부의 트랜잭션이 롤백되면 응원 기록도 함께 사라진다. 시각은 방언 모듈(`SqlDialect`)이 바인딩한다.
 */
@Repository
class CheerRepository(private val jdbc: JdbcClient, private val dialect: SqlDialect) {
    /** 같은 주문은 한 줄이다 — 두 번째 호출은 아무것도 하지 않는다(멱등). */
    fun insertPaid(cheer: Cheer) {
        jdbc.sql(dialect.insertIgnore("cheers", listOf("order_id", "account_id", "amount", "currency", "refunded_amount", "status", "created_at"), listOf("order_id")))
            .param("order_id", cheer.orderId)
            .param("account_id", cheer.accountId)
            .param("amount", cheer.amount)
            .param("currency", cheer.currency)
            .param("refunded_amount", cheer.refundedAmount)
            .param("status", cheer.status.name)
            .param("created_at", dialect.instantParam(cheer.createdAt))
            .update()
    }

    /**
     * 환불을 반영한다 — 절대값이라 두 번 불려도 같다. 기록이 없으면(지급한 적 없는 주문 · 이미 지워진 계정) 아무 일도 없다. [full] 이면 `REFUNDED` + 환불 시각(처음 값을 지킨다).
     * 환불된 금액은 줄어들지 않는다(늦게 온 낡은 호출이 값을 되돌리지 못한다).
     */
    fun applyRefund(orderId: String, refundedAmount: Long, full: Boolean, at: Instant): Int =
        jdbc.sql(
            """
            update cheers set
                refunded_amount = greatest(refunded_amount, :refunded),
                status = case when :full then 'REFUNDED' else status end,
                refunded_at = case when :full then coalesce(refunded_at, :at) else refunded_at end
            where order_id = :order_id
            """.trimIndent(),
        )
            .param("refunded", refundedAmount)
            .param("full", full)
            .param("at", dialect.instantParam(at))
            .param("order_id", orderId)
            .update()

    /** 내 응원 내역, 최신순(시각, 같으면 주문 id 가 큰 쪽). [limit] 줄까지. */
    fun listByAccount(accountId: String, before: CheerCursor?, limit: Int): List<Cheer> {
        val cursorClause = if (before == null) "" else "and (created_at < :c_at or (created_at = :c_at and order_id < :c_id))"
        var statement = jdbc.sql(
            "select order_id, account_id, amount, currency, refunded_amount, status, created_at, refunded_at from cheers " +
                "where account_id = :account_id $cursorClause order by created_at desc, order_id desc limit :limit",
        ).param("account_id", accountId).param("limit", limit)
        if (before != null) statement = statement.param("c_at", dialect.instantParam(before.createdAt)).param("c_id", before.orderId)
        return statement.query { rs, _ ->
            Cheer(
                orderId = rs.getString("order_id"),
                accountId = rs.getString("account_id"),
                amount = rs.getLong("amount"),
                currency = rs.getString("currency"),
                refundedAmount = rs.getLong("refunded_amount"),
                status = CheerStatus.valueOf(rs.getString("status")),
                createdAt = requireNotNull(dialect.readInstant(rs, "created_at")),
                refundedAt = dialect.readInstant(rs, "refunded_at"),
            )
        }.list()
    }

    /** 계정이 지워질 때 — 그 계정의 응원 기록을 지운다(멱등). 장부의 주문 줄은 법정 보존이라 남는다. 지운 줄 수. */
    fun deleteByAccount(accountId: String): Int =
        jdbc.sql("delete from cheers where account_id = :account_id").param("account_id", accountId).update()
}
