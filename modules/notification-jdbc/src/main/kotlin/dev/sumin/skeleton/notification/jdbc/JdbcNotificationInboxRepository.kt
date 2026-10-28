package dev.sumin.skeleton.notification.jdbc

import dev.sumin.skeleton.json.JsonCodec
import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationInboxPage
import dev.sumin.skeleton.notification.NotificationInboxQuery
import dev.sumin.skeleton.notification.NotificationInboxRecord
import dev.sumin.skeleton.notification.NotificationInboxRepository
import dev.sumin.skeleton.notification.NotificationSeverity
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.sql.ResultSet
import java.time.Instant
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * 받은편지함 표 `notification_inbox`. 같은 열쇠([NotificationEvent.collapseKey]) 묶기는 DB 가 원자적으로 막는다:
 * `unread_collapse_key`(안 읽은 묶음 줄만 값, 읽으면 null)에 `(recipient_id, unread_collapse_key)` 유니크가 있어 같은 열쇠 안 읽은 줄은 사람마다 하나뿐이다.
 * 저장은 ① 넣기(**어느 유니크 충돌이든 무시**) ② 내 이벤트 줄이 있으면 끝(새로 넣었거나 같은 이벤트의 재발행) ③ 없으면 안 읽은 같은 열쇠 줄이 있다는 뜻이라 그 줄을 접는다 -
 * 그 사이 읽혀 열쇠가 풀렸으면(접기가 0 줄) ① 부터 다시. 접기 update 는 칼럼 값을 실제로 바꾸므로 갱신 행 수를 믿을 수 있다 - 넣기의 갱신 행 수(MySQL 은 이미 있어도 1)는 쓰지 않는다.
 * **접기를 먼저 시도하지 않는 이유(실측, MySQL 8.4)**: 없는 열쇠를 찾는 update 는 REPEATABLE READ 에서 간격 잠금을 걸어, 같은 열쇠를 동시에 저장하는 두 트랜잭션이 서로의 간격에 넣으려다 교착한다.
 * 넣기를 먼저 하면 뒤 쪽은 앞 쪽 커밋까지 기다렸다가 충돌을 무시하고 접는다 (PostgreSQL 도 같다).
 * 부르는 쪽 트랜잭션 안에서 돈다(롤백되면 같이 사라진다). 트랜잭션이 없으면 문장마다 커밋된다 - 같은 규칙이 성립한다.
 */
class JdbcNotificationInboxRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val jsonCodec: JsonCodec,
    private val dialect: SqlDialect,
) : NotificationInboxRepository {
    override fun save(
        event: NotificationEvent,
        recipientIds: Set<String>,
    ): List<NotificationInboxRecord> =
        // 여러 받는 사람을 늘 같은 순서로 저장한다 - 겹치는 발행끼리 서로 반대 순서로 잠그다 교착하지 않게
        recipientIds.map { it.trim() }.sorted().mapNotNull { recipientId ->
            val normalizedRecipientId = recipientId.trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val collapseKey = event.collapseKey
            if (collapseKey == null) {
                insertIfAbsent(event, normalizedRecipientId)
                findOne(normalizedRecipientId, event.id)
            } else {
                saveCollapsing(event, normalizedRecipientId, collapseKey)
            }
        }

    private fun saveCollapsing(
        event: NotificationEvent,
        recipientId: String,
        collapseKey: String,
    ): NotificationInboxRecord? {
        // 이미 저장했거나 이 열쇠 줄에 접힌 이벤트 id 의 재발행(재시도)은 아무것도 바꾸지 않는다 - 접힌 뒤 읽혔어도 새 줄이 생기지 않는다
        findSeen(recipientId, event.id)?.let { return it }
        repeat(MAX_COLLAPSE_ATTEMPTS) {
            insertIfAbsent(event, recipientId)
            findOne(recipientId, event.id)?.let { return it }
            if (foldIntoUnread(event, recipientId, collapseKey)) return findUnreadByKey(recipientId, collapseKey)
            // 접기가 0 줄: 같은 이벤트를 동시에 저장한 쪽이 먼저 반영했을 수 있다. MySQL REPEATABLE READ 의 일반 읽기는 스냅샷이라 못 보므로 잠그는 읽기(최신)로 다시 본다
            findSeen(recipientId, event.id, forShare = true)?.let { return it }
        }
        // 알림이 업무 트랜잭션을 깨면 안 된다 - 경고만 남기고 이 받는 사람의 저장을 건너뛴다
        log.warn("notification {} was not stored for {}: the collapse row kept changing under it", event.id, recipientId)
        return null
    }

    /** 그 이벤트 id 가 줄의 처음 id(`event_id`)이거나 마지막으로 접힌 id(`last_event_id`)인 줄 */
    private fun findSeen(
        recipientId: String,
        eventId: String,
        forShare: Boolean = false,
    ): NotificationInboxRecord? =
        jdbc.query(
            "select * from notification_inbox where recipient_id = :recipientId and (event_id = :eventId or last_event_id = :eventId)" +
                if (forShare) " for share" else "",
            MapSqlParameterSource().addValue("recipientId", recipientId).addValue("eventId", eventId),
        ) { rs, _ -> rs.toRecord() }
            .firstOrNull()

    /**
     * 안 읽은 같은 열쇠 줄을 새 내용으로 고치고 수를 올린다. `event_id <> :eventId` 와 `last_event_id <> :eventId` - 그 줄의 처음 이벤트나
     * 방금 접힌 이벤트를 다시 발행해도(동시 재시도) 수가 오르지 않는다.
     */
    private fun foldIntoUnread(
        event: NotificationEvent,
        recipientId: String,
        collapseKey: String,
    ): Boolean =
        jdbc.update(
            """
            update notification_inbox
            set topic = :topic,
                type = :type,
                severity = :severity,
                title = :title,
                message = :message,
                payload_json = :payload_json,
                link = :link,
                event_created_at = :event_created_at,
                updated_at = :updated_at,
                last_event_id = :event_id,
                collapsed_count = collapsed_count + 1
            where recipient_id = :recipient_id
              and unread_collapse_key = :collapse_key
              and event_id <> :event_id
              and (last_event_id is null or last_event_id <> :event_id)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("recipient_id", recipientId)
                .addValue("collapse_key", collapseKey)
                .addValue("event_id", event.id)
                .addValue("topic", event.topic)
                .addValue("type", event.type)
                .addValue("severity", event.severity.name)
                .addValue("title", event.title)
                .addValue("message", event.message)
                .addValue("payload_json", jsonCodec.canonicalString(jsonCodec.toDocument(event.payload)))
                .addValue("link", event.link)
                .addValue("event_created_at", dialect.instantParam(event.createdAt))
                .addValue("updated_at", dialect.instantParam(Instant.now())),
        ) > 0

    private fun findUnreadByKey(
        recipientId: String,
        collapseKey: String,
    ): NotificationInboxRecord? =
        jdbc.query(
            "select * from notification_inbox where recipient_id = :recipientId and unread_collapse_key = :collapseKey",
            MapSqlParameterSource().addValue("recipientId", recipientId).addValue("collapseKey", collapseKey),
        ) { rs, _ -> rs.toRecord() }
            .firstOrNull()

    override fun findByRecipient(
        recipientId: String,
        query: NotificationInboxQuery,
    ): NotificationInboxPage {
        val where = whereClause(query)
        val parameters = MapSqlParameterSource()
            .addValue("recipientId", recipientId.trim())
            .addValue("topic", query.topic?.trim())
            .addValue("limit", query.size)
            .addValue("offset", query.offset())

        val total = jdbc.queryForObject(
            "select count(*) from notification_inbox $where",
            parameters,
            Long::class.java,
        ) ?: 0L
        val values = jdbc.query(
            """
            select *
            from notification_inbox
            $where
            order by event_created_at desc, event_id desc
            limit :limit offset :offset
            """.trimIndent(),
            parameters,
        ) { rs, _ -> rs.toRecord() }

        return NotificationInboxPage(values = values, totalElements = total)
    }

    override fun markRead(
        recipientId: String,
        eventId: String,
        readAt: Instant,
    ): NotificationInboxRecord? {
        jdbc.update(
            """
            update notification_inbox
            set read_at = coalesce(read_at, :readAt),
                unread_collapse_key = null,
                updated_at = :readAt
            where recipient_id = :recipientId
              and event_id = :eventId
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("recipientId", recipientId.trim())
                .addValue("eventId", eventId.trim())
                .addValue("readAt", dialect.instantParam(readAt)),
        )
        return findOne(recipientId.trim(), eventId.trim())
    }

    override fun markAllRead(
        recipientId: String,
        readAt: Instant,
    ): Int =
        jdbc.update(
            """
            update notification_inbox
            set read_at = :readAt,
                unread_collapse_key = null,
                updated_at = :readAt
            where recipient_id = :recipientId
              and read_at is null
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("recipientId", recipientId.trim())
                .addValue("readAt", dialect.instantParam(readAt)),
        )

    override fun countUnread(recipientId: String): Long =
        jdbc.queryForObject(
            "select count(*) from notification_inbox where recipient_id = :recipientId and read_at is null",
            MapSqlParameterSource().addValue("recipientId", recipientId.trim()),
            Long::class.java,
        ) ?: 0L

    override fun deleteOlderThan(cutoff: Instant): Int =
        deleteInBatches("event_created_at < :cutoff", MapSqlParameterSource().addValue("cutoff", dialect.instantParam(cutoff)))

    override fun deleteByTopic(topic: String): Int =
        deleteInBatches("topic = :topic", MapSqlParameterSource().addValue("topic", topic))

    override fun deleteByRecipientAndTopic(
        recipientId: String,
        topic: String,
    ): Int =
        deleteInBatches(
            "recipient_id = :recipientId and topic = :topic",
            MapSqlParameterSource().addValue("recipientId", recipientId.trim()).addValue("topic", topic),
        )

    /** `select … limit` 으로 기본 키를 모아 `delete … in (…)` - 큰 표를 한 문장으로 지우지 않는다 (JdbcJobRepository.deleteFinishedBefore 꼴) */
    private fun deleteInBatches(
        condition: String,
        parameters: MapSqlParameterSource,
    ): Int {
        var total = 0
        while (true) {
            val keys = jdbc.query(
                "select recipient_id, event_id from notification_inbox where $condition limit $DELETE_BATCH_SIZE",
                parameters,
            ) { rs, _ -> arrayOf<Any>(rs.getString("recipient_id"), rs.getString("event_id")) }
            if (keys.isEmpty()) return total
            val deleted = jdbc.update(
                "delete from notification_inbox where (recipient_id, event_id) in (:keys) and $condition",
                MapSqlParameterSource(parameters.values).addValue("keys", keys),
            )
            if (deleted == 0) return total   // 다른 인스턴스가 먼저 지웠다 - 멱등이라 여기서 끝내도 같다
            total += deleted
        }
    }

    /** 어느 유니크 충돌이든 무시 - 기본 키(같은 이벤트 재발행)와 안 읽은 열쇠 유니크(같은 열쇠를 동시에 넣은 쪽에 짐) 둘 다 */
    private fun insertIfAbsent(event: NotificationEvent, recipientId: String) {
        val now = Instant.now()
        jdbc.update(
            dialect.insertIgnore(TABLE, INSERT_COLUMNS, emptyList()),
            MapSqlParameterSource()
                .addValue("recipient_id", recipientId)
                .addValue("event_id", event.id)
                .addValue("topic", event.topic)
                .addValue("type", event.type)
                .addValue("severity", event.severity.name)
                .addValue("title", event.title)
                .addValue("message", event.message)
                .addValue("payload_json", jsonCodec.canonicalString(jsonCodec.toDocument(event.payload)))
                .addValue("recipient_ids_json", jsonCodec.canonicalString(jsonCodec.toDocument(event.recipientIds)))
                .addValue("event_created_at", dialect.instantParam(event.createdAt))
                .addValue("link", event.link)
                .addValue("collapse_key", event.collapseKey)
                .addValue("unread_collapse_key", event.collapseKey)
                .addValue("last_event_id", event.collapseKey?.let { event.id })
                .addValue("collapsed_count", 1)
                .addValue("created_at", dialect.instantParam(now))
                .addValue("updated_at", dialect.instantParam(now)),
        )
    }

    private fun findOne(
        recipientId: String,
        eventId: String,
    ): NotificationInboxRecord? =
        jdbc.query(
            """
            select *
            from notification_inbox
            where recipient_id = :recipientId
              and event_id = :eventId
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("recipientId", recipientId)
                .addValue("eventId", eventId),
        ) { rs, _ -> rs.toRecord() }
            .firstOrNull()

    private fun whereClause(query: NotificationInboxQuery): String =
        buildString {
            append("where recipient_id = :recipientId")
            if (query.unreadOnly) {
                append(" and read_at is null")
            }
            if (!query.topic.isNullOrBlank()) {
                append(" and topic = :topic")
            }
        }

    private fun ResultSet.toRecord(): NotificationInboxRecord {
        val event = NotificationEvent(
            id = getString("event_id"),
            topic = getString("topic"),
            type = getString("type"),
            recipientIds = readRecipientIds(getString("recipient_ids_json")),
            severity = NotificationSeverity.valueOf(getString("severity")),
            title = getString("title"),
            message = getString("message"),
            payload = readPayload(getString("payload_json")),
            createdAt = dialect.readInstant(this, "event_created_at")!!,
            link = getString("link"),
            collapseKey = getString("collapse_key"),
        )
        return NotificationInboxRecord(
            recipientId = getString("recipient_id"),
            event = event,
            readAt = dialect.readInstant(this, "read_at"),
            collapsedCount = getInt("collapsed_count"),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun readPayload(raw: String): Map<String, Any?> =
        jsonCodec.fromDocument(jsonCodec.parse(raw), Map::class) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun readRecipientIds(raw: String): Set<String> =
        (jsonCodec.fromDocument(jsonCodec.parse(raw), Set::class) as Set<Any?>)
            .mapNotNullTo(linkedSetOf()) { value ->
                value?.toString()?.trim()?.takeIf { it.isNotBlank() }
            }

    private companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(JdbcNotificationInboxRepository::class.java)
        const val TABLE = "notification_inbox"

        /** read_at 은 빼서 칼럼 기본값(null) — PG 는 타입 없는 null 파라미터를 추론하지 못할 수 있다 */
        val INSERT_COLUMNS = listOf(
            "recipient_id", "event_id", "topic", "type", "severity", "title", "message",
            "payload_json", "recipient_ids_json", "event_created_at", "link", "collapse_key", "unread_collapse_key",
            "collapsed_count", "last_event_id", "created_at", "updated_at",
        )

        /** 넣기와 접기 사이에 그 줄이 읽혀 열쇠가 풀리는 경우만 다시 돈다 - 보통 한 번이면 끝난다 */
        const val MAX_COLLAPSE_ATTEMPTS = 3

        /** 지우기 한 번에 지우는 줄 수 - 큰 DELETE 가 표를 오래 잠그지 않게 나눈다 */
        const val DELETE_BATCH_SIZE = 1000
    }
}
