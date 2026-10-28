package dev.sumin.skeleton.notification.jdbc

import dev.sumin.skeleton.notification.NotificationMuteRepository
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.time.Instant
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/** 표 `notification_mutes` - 끈 것만 줄이 있다. 켜면 줄을 지우고, 끄기는 이미 있어도 한 줄이다([SqlDialect.insertIgnore]) */
class JdbcNotificationMuteRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val dialect: SqlDialect,
) : NotificationMuteRepository {
    override fun mutedTypes(recipientId: String): Set<String> =
        jdbc.queryForList(
            "select type from notification_mutes where recipient_id = :recipientId",
            MapSqlParameterSource().addValue("recipientId", recipientId.trim()),
            String::class.java,
        ).filterNotNullTo(linkedSetOf())

    override fun mutedAmong(
        type: String,
        recipientIds: Set<String>,
    ): Set<String> =
        recipientIds.map { it.trim() }.chunked(IN_LIST_CHUNK).flatMapTo(linkedSetOf<String>()) { chunk ->
            jdbc.queryForList(
                "select recipient_id from notification_mutes where type = :type and recipient_id in (:ids)",
                MapSqlParameterSource().addValue("type", type).addValue("ids", chunk),
                String::class.java,
            ).filterNotNull()
        }

    override fun setMuted(
        recipientId: String,
        type: String,
        muted: Boolean,
    ) {
        val id = recipientId.trim()
        if (muted) {
            jdbc.update(
                dialect.insertIgnore(TABLE, listOf("recipient_id", "type", "created_at"), listOf("recipient_id", "type")),
                MapSqlParameterSource()
                    .addValue("recipient_id", id)
                    .addValue("type", type)
                    .addValue("created_at", dialect.instantParam(Instant.now())),
            )
        } else {
            jdbc.update(
                "delete from notification_mutes where recipient_id = :recipientId and type = :type",
                MapSqlParameterSource().addValue("recipientId", id).addValue("type", type),
            )
        }
    }

    private companion object {
        const val TABLE = "notification_mutes"

        /** `in (…)` 목록이 길어져도 드라이버의 파라미터 한도를 넘지 않게 나눈다 */
        const val IN_LIST_CHUNK = 500
    }
}
