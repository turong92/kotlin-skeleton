package dev.sumin.skeleton.notification.broadcast.jdbc

import dev.sumin.skeleton.notification.broadcast.Broadcast
import dev.sumin.skeleton.notification.broadcast.BroadcastAudience
import dev.sumin.skeleton.notification.broadcast.BroadcastContent
import dev.sumin.skeleton.notification.broadcast.BroadcastStatus
import dev.sumin.skeleton.notification.broadcast.BroadcastStore
import dev.sumin.skeleton.notification.broadcast.ChannelStats
import dev.sumin.skeleton.notification.broadcast.StatField
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.sql.ResultSet
import java.time.Instant
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionOperations

/**
 * 표 `broadcasts`(공지 한 줄) · `broadcast_channel_stats`(고른 채널마다 한 줄 - 고리가 원자 증가로 센다). 받는 사람 데이터는 없다(수만 명을 담지 않는다).
 * 상태 전이는 모두 `where status = …` 조건부 갱신이라 동시에 눌러도 하나만 1 줄을 갱신한다. 단계 작업은 [findForUpdate] 로 줄을 잠근다(PostgreSQL · MySQL 모두 `for update`).
 */
class JdbcBroadcastStore(
    private val jdbc: NamedParameterJdbcTemplate,
    private val transactions: TransactionOperations,
    private val dialect: SqlDialect,
) : BroadcastStore {
    override fun <T> transaction(block: () -> T): T = transactions.execute { block() }

    override fun insert(broadcast: Broadcast) {
        val c = broadcast.content
        jdbc.update(
            """
            insert into broadcasts (id, status, type, title, body, link, channels, audience_kind, audience_param, scheduled_at, run_token, next_seq, cursor_id,
                estimate, recipients, created_by, sent_by, cancelled_by, created_at, updated_at, send_requested_at, started_at, finished_at, cancelled_at, failure_reason)
            values (:id, :status, :type, :title, :body, :link, :channels, :audience_kind, :audience_param, :scheduled_at, :run_token, :next_seq, :cursor_id,
                :estimate, :recipients, :created_by, :sent_by, :cancelled_by, :created_at, :updated_at, :send_requested_at, :started_at, :finished_at, :cancelled_at, :failure_reason)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", broadcast.id).addValue("status", broadcast.status.name)
                .addValue("type", c.type).addValue("title", c.title).addValue("body", c.body).addValue("link", c.link)
                .addValue("channels", c.channels.joinToString(",")).addValue("audience_kind", c.audience.kind).addValue("audience_param", c.audience.param)
                .addValue("scheduled_at", dialect.instantParam(broadcast.scheduledAt)).addValue("run_token", broadcast.runToken)
                .addValue("next_seq", broadcast.nextSeq).addValue("cursor_id", broadcast.cursorId)
                .addValue("estimate", broadcast.estimate).addValue("recipients", broadcast.recipients)
                .addValue("created_by", broadcast.createdBy).addValue("sent_by", broadcast.sentBy).addValue("cancelled_by", broadcast.cancelledBy)
                .addValue("created_at", dialect.instantParam(broadcast.createdAt)).addValue("updated_at", dialect.instantParam(broadcast.updatedAt))
                .addValue("send_requested_at", dialect.instantParam(broadcast.sendRequestedAt)).addValue("started_at", dialect.instantParam(broadcast.startedAt))
                .addValue("finished_at", dialect.instantParam(broadcast.finishedAt)).addValue("cancelled_at", dialect.instantParam(broadcast.cancelledAt))
                .addValue("failure_reason", broadcast.failureReason),
        )
    }

    override fun find(id: String): Broadcast? = one("select * from broadcasts where id = :id", id)

    override fun findForUpdate(id: String): Broadcast? = one("select * from broadcasts where id = :id for update", id)

    override fun statusOf(id: String): BroadcastStatus? =
        jdbc.query("select status from broadcasts where id = :id", mapOf("id" to id)) { rs, _ -> BroadcastStatus.valueOf(rs.getString(1)) }.firstOrNull()

    override fun list(status: BroadcastStatus?, before: String?, limit: Int): List<Broadcast> {
        val where = listOfNotNull("status = :status".takeIf { status != null }, "id < :before".takeIf { before != null })
        val params = MapSqlParameterSource().addValue("limit", limit)
        status?.let { params.addValue("status", it.name) }
        before?.let { params.addValue("before", it) }
        return jdbc.query("select * from broadcasts ${if (where.isEmpty()) "" else "where " + where.joinToString(" and ")} order by id desc limit :limit", params) { rs, _ -> map(rs) }
    }

    override fun updateDraft(id: String, content: BroadcastContent, at: Instant): Boolean =
        update(
            "update broadcasts set type = :type, title = :title, body = :body, link = :link, channels = :channels, audience_kind = :kind, audience_param = :param, updated_at = :at where id = :id and status = 'DRAFT'",
            id, "type" to content.type, "title" to content.title, "body" to content.body, "link" to content.link, "channels" to content.channels.joinToString(","),
            "kind" to content.audience.kind, "param" to content.audience.param, "at" to dialect.instantParam(at),
        )

    override fun deleteDraft(id: String): Boolean = update("delete from broadcasts where id = :id and status = 'DRAFT'", id)

    override fun start(id: String, token: String, scheduledAt: Instant?, estimate: Long, by: String, at: Instant): Boolean =
        update(
            """
            update broadcasts set status = :status, run_token = :token, next_seq = 0, cursor_id = null, estimate = :estimate, recipients = 0, scheduled_at = :scheduled,
                sent_by = :by, send_requested_at = :at, started_at = null, finished_at = null, failure_reason = null, updated_at = :at
            where id = :id and status = 'DRAFT'
            """.trimIndent(),
            id, "status" to (if (scheduledAt != null) BroadcastStatus.SCHEDULED else BroadcastStatus.SENDING).name, "token" to token, "estimate" to estimate,
            "scheduled" to dialect.instantParam(scheduledAt), "by" to by, "at" to dialect.instantParam(at),
        )

    override fun unschedule(id: String, at: Instant): Boolean =
        update(
            "update broadcasts set status = 'DRAFT', run_token = null, next_seq = 0, scheduled_at = null, estimate = null, sent_by = null, send_requested_at = null, updated_at = :at where id = :id and status = 'SCHEDULED'",
            id, "at" to dialect.instantParam(at),
        )

    override fun cancel(id: String, by: String, at: Instant): Boolean =
        update(
            "update broadcasts set status = 'CANCELLED', cancelled_by = :by, cancelled_at = :at, updated_at = :at where id = :id and status in ('SCHEDULED', 'SENDING')",
            id, "by" to by, "at" to dialect.instantParam(at),
        )

    override fun resume(id: String, token: String, at: Instant): Boolean =
        update(
            "update broadcasts set status = 'SENDING', run_token = :token, failure_reason = null, updated_at = :at where id = :id and status = 'FAILED'",
            id, "token" to token, "at" to dialect.instantParam(at),
        )

    override fun fail(id: String, token: String, reason: String, at: Instant): Boolean =
        update(
            "update broadcasts set status = 'FAILED', failure_reason = :reason, updated_at = :at where id = :id and run_token = :token and status in ('SCHEDULED', 'SENDING')",
            id, "token" to token, "reason" to reason, "at" to dialect.instantParam(at),
        )

    override fun advance(id: String, cursorId: String, processed: Int, at: Instant) {
        update(
            """
            update broadcasts set status = 'SENDING', cursor_id = :cursor, next_seq = next_seq + 1, recipients = recipients + :n,
                started_at = coalesce(started_at, :at), updated_at = :at
            where id = :id and status in ('SCHEDULED', 'SENDING')
            """.trimIndent(),
            id, "cursor" to cursorId, "n" to processed, "at" to dialect.instantParam(at),
        )
    }

    override fun finish(id: String, at: Instant) {
        update(
            "update broadcasts set status = 'SENT', cursor_id = null, finished_at = :at, started_at = coalesce(started_at, :at), updated_at = :at where id = :id and status in ('SCHEDULED', 'SENDING')",
            id, "at" to dialect.instantParam(at),
        )
    }

    override fun openChannels(id: String, channels: List<String>) {
        jdbc.update("delete from broadcast_channel_stats where broadcast_id = :id", mapOf("id" to id))
        channels.forEach {
            jdbc.update("insert into broadcast_channel_stats (broadcast_id, channel, queued, delivered, failed, dropped, opened) values (:id, :channel, 0, 0, 0, 0, 0)", mapOf("id" to id, "channel" to it))
        }
    }

    override fun channelStats(id: String): Map<String, ChannelStats> =
        jdbc.query("select channel, queued, delivered, failed, dropped, opened from broadcast_channel_stats where broadcast_id = :id order by channel", mapOf("id" to id)) { rs, _ ->
            rs.getString("channel") to ChannelStats(rs.getLong("queued"), rs.getLong("delivered"), rs.getLong("failed"), rs.getLong("dropped"), rs.getLong("opened"))
        }.toMap(linkedMapOf())

    override fun bump(id: String, channel: String, field: StatField, by: Int) {
        val column = field.name.lowercase()   // 열거형 이름만 SQL 에 들어간다
        jdbc.update("update broadcast_channel_stats set $column = $column + :by where broadcast_id = :id and channel = :channel", mapOf("by" to by, "id" to id, "channel" to channel))
    }

    // -----------------------------------------------------------------------------------------------------------------

    private fun one(sql: String, id: String): Broadcast? = jdbc.query(sql, mapOf("id" to id)) { rs, _ -> map(rs) }.firstOrNull()

    private fun update(sql: String, id: String, vararg params: Pair<String, Any?>): Boolean =
        jdbc.update(sql, MapSqlParameterSource(mapOf("id" to id) + params)) > 0

    private fun map(rs: ResultSet): Broadcast = Broadcast(
        id = rs.getString("id"),
        status = BroadcastStatus.valueOf(rs.getString("status")),
        content = BroadcastContent(
            type = rs.getString("type"), title = rs.getString("title"), body = rs.getString("body"), link = rs.getString("link"),
            channels = rs.getString("channels").split(',').filter { it.isNotEmpty() },
            audience = BroadcastAudience(rs.getString("audience_kind"), rs.getString("audience_param")),
        ),
        scheduledAt = dialect.readInstant(rs, "scheduled_at"),
        runToken = rs.getString("run_token"),
        nextSeq = rs.getInt("next_seq"),
        cursorId = rs.getString("cursor_id"),
        estimate = rs.getLong("estimate").takeUnless { rs.wasNull() },
        recipients = rs.getLong("recipients"),
        createdBy = rs.getString("created_by"),
        sentBy = rs.getString("sent_by"),
        cancelledBy = rs.getString("cancelled_by"),
        createdAt = checkNotNull(dialect.readInstant(rs, "created_at")),
        updatedAt = checkNotNull(dialect.readInstant(rs, "updated_at")),
        sendRequestedAt = dialect.readInstant(rs, "send_requested_at"),
        startedAt = dialect.readInstant(rs, "started_at"),
        finishedAt = dialect.readInstant(rs, "finished_at"),
        cancelledAt = dialect.readInstant(rs, "cancelled_at"),
        failureReason = rs.getString("failure_reason"),
    )
}
