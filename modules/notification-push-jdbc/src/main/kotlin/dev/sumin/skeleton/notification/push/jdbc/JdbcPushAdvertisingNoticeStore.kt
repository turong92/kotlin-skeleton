package dev.sumin.skeleton.notification.push.jdbc

import dev.sumin.skeleton.notification.push.PushAdvertisingNoticeStore
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.time.Instant
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/** 표 `push_advertising_notices` - 재확인 안내를 이 동의 주기에 보냈다는 표시. 두 번 표시해도 한 줄이다([SqlDialect.insertIgnore]) */
class JdbcPushAdvertisingNoticeStore(
    private val jdbc: NamedParameterJdbcTemplate,
    private val dialect: SqlDialect,
) : PushAdvertisingNoticeStore {
    override fun isNoticed(accountId: String, type: String, agreedAt: Instant): Boolean =
        jdbc.queryForObject(
            "select count(*) from push_advertising_notices where account_id = :a and type = :t and agreed_at = :at",
            MapSqlParameterSource().addValue("a", accountId).addValue("t", type).addValue("at", dialect.instantParam(agreedAt)), Long::class.java,
        )!! > 0

    override fun markNoticed(accountId: String, type: String, agreedAt: Instant, at: Instant) {
        jdbc.update(
            dialect.insertIgnore("push_advertising_notices", listOf("account_id", "type", "agreed_at", "notified_at"), listOf("account_id", "type", "agreed_at")),
            MapSqlParameterSource().addValue("account_id", accountId).addValue("type", type)
                .addValue("agreed_at", dialect.instantParam(agreedAt)).addValue("notified_at", dialect.instantParam(at)),
        )
    }
}
