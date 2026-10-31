package dev.sumin.skeleton.notification.push.jdbc

import dev.sumin.skeleton.notification.push.PushTypeMuteStore
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.time.Instant
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/** 표 `push_type_mutes` - 푸시만 끈 것만 줄이 있다. 켜면 줄을 지우고, 끄기는 이미 있어도 한 줄이다([SqlDialect.insertIgnore]) */
class JdbcPushTypeMuteStore(
    private val jdbc: NamedParameterJdbcTemplate,
    private val dialect: SqlDialect,
) : PushTypeMuteStore {
    override fun mutedTypes(accountId: String): Set<String> =
        jdbc.queryForList("select type from push_type_mutes where account_id = :a", mapOf("a" to accountId), String::class.java)
            .filterNotNullTo(linkedSetOf())

    override fun mutedAmong(type: String, accountIds: Set<String>): Set<String> =
        accountIds.toList().chunked(IN_LIST_CHUNK).flatMapTo(linkedSetOf<String>()) { chunk ->
            jdbc.queryForList(
                "select account_id from push_type_mutes where type = :type and account_id in (:ids)",
                mapOf("type" to type, "ids" to chunk), String::class.java,
            ).filterNotNull()
        }

    override fun setMuted(accountId: String, type: String, muted: Boolean) {
        if (muted) {
            jdbc.update(
                dialect.insertIgnore("push_type_mutes", listOf("account_id", "type", "created_at"), listOf("account_id", "type")),
                MapSqlParameterSource().addValue("account_id", accountId).addValue("type", type).addValue("created_at", dialect.instantParam(Instant.now())),
            )
        } else {
            jdbc.update("delete from push_type_mutes where account_id = :a and type = :t", mapOf("a" to accountId, "t" to type))
        }
    }

    private companion object {
        /** `in (…)` 목록이 길어져도 드라이버의 파라미터 한도를 넘지 않게 나눈다 */
        const val IN_LIST_CHUNK = 500
    }
}
