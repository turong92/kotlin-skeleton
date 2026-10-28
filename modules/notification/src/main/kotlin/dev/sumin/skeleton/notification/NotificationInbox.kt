package dev.sumin.skeleton.notification

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class NotificationInboxRecord(
    val recipientId: String,
    val event: NotificationEvent,
    val readAt: Instant? = null,
    /** 같은 [NotificationEvent.collapseKey] 로 접힌 알림 수. 접히지 않았으면 1 */
    val collapsedCount: Int = 1,
) {
    val id: String = "$recipientId:${event.id}"
}

data class NotificationInboxQuery(
    val page: Int = 0,
    val size: Int = 20,
    val unreadOnly: Boolean = false,
    val topic: String? = null,
) {
    init {
        require(page >= 0) { "page must be greater than or equal to 0" }
        require(size in 1..100) { "size must be between 1 and 100" }
    }

    fun offset(): Int = page * size
}

data class NotificationInboxPage(
    val values: List<NotificationInboxRecord>,
    val totalElements: Long,
)

interface NotificationInboxRepository {
    /**
     * 받는 사람마다 한 줄을 넣는다. [NotificationEvent.collapseKey] 가 있으면 그 사람의 **안 읽은** 같은 열쇠 줄에 접는다:
     * 내용(`type` · `severity` · `title` · `message` · `payload` · `link`)과 시각은 새 것, `collapsedCount` +1, `eventId` 는 처음 것 그대로(읽음 주소가 바뀌지 않게).
     * 읽은 줄은 건드리지 않고 새 줄을 만든다. 같은 이벤트 id 를 다시 저장해도 수는 오르지 않는다.
     */
    fun save(
        event: NotificationEvent,
        recipientIds: Set<String>,
    ): List<NotificationInboxRecord>

    fun findByRecipient(
        recipientId: String,
        query: NotificationInboxQuery = NotificationInboxQuery(),
    ): NotificationInboxPage

    fun markRead(
        recipientId: String,
        eventId: String,
        readAt: Instant = Instant.now(),
    ): NotificationInboxRecord?

    fun markAllRead(
        recipientId: String,
        readAt: Instant = Instant.now(),
    ): Int

    /** 안 읽은 줄 수. 기본 구현은 목록 조회의 전체 수라 직접 만든 저장소도 깨지지 않는다 - 저장소가 더 싸게 셀 수 있으면 덮는다 */
    fun countUnread(recipientId: String): Long =
        findByRecipient(recipientId, NotificationInboxQuery(size = 1, unreadOnly = true)).totalElements

    /** `event_created_at < cutoff`(같은 시각은 남긴다)인 줄을 읽음 여부와 무관하게 지운다. 지운 줄 수를 돌려준다 */
    fun deleteOlderThan(cutoff: Instant): Int

    /** 그 주제의 모든 사람의 줄을 지운다 (서버 API 만 - HTTP 로는 열지 않는다) */
    fun deleteByTopic(topic: String): Int

    fun deleteByRecipientAndTopic(
        recipientId: String,
        topic: String,
    ): Int
}

fun interface NotificationRecipientResolver {
    fun resolveRecipients(event: NotificationEvent): Set<String>
}

class DefaultNotificationRecipientResolver : NotificationRecipientResolver {
    override fun resolveRecipients(event: NotificationEvent): Set<String> =
        buildSet {
            event.recipientIds.forEach { addNormalized(it) }
            addPayloadValue(event.payload["recipientId"])
            addPayloadValue(event.payload["userId"])
            addPayloadValue(event.payload["accountId"])
            addPayloadValue(event.payload["recipientIds"])
            addPayloadValue(event.payload["userIds"])
            addPayloadValue(event.payload["accountIds"])
        }

    private fun MutableSet<String>.addPayloadValue(value: Any?) {
        when (value) {
            is Iterable<*> -> value.forEach { addPayloadValue(it) }
            is Array<*> -> value.forEach { addPayloadValue(it) }
            null -> Unit
            else -> addNormalized(value.toString())
        }
    }

    private fun MutableSet<String>.addNormalized(value: String) {
        value.trim()
            .takeIf { it.isNotBlank() }
            ?.let { add(it) }
    }
}

class InMemoryNotificationInboxRepository : NotificationInboxRepository {
    private val records = ConcurrentHashMap<NotificationInboxKey, NotificationInboxRecord>()

    /** 묶기는 「안 읽은 같은 열쇠 줄을 찾고 고친다」가 한 덩어리여야 해서 저장을 줄 세운다 (DB 판은 유니크 색인이 같은 일을 한다) */
    @Synchronized
    override fun save(
        event: NotificationEvent,
        recipientIds: Set<String>,
    ): List<NotificationInboxRecord> =
        recipientIds.mapNotNull { recipientId ->
            val normalizedRecipientId = recipientId.trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val project = project()
            val key = NotificationInboxKey(normalizedRecipientId, event.id, project)
            records[key]?.let { return@mapNotNull it }
            val folded = event.collapseKey?.let { collapseKey -> findUnreadFolding(normalizedRecipientId, collapseKey, project) }
            if (folded == null) {
                NotificationInboxRecord(recipientId = normalizedRecipientId, event = event).also { records[key] = it }
            } else {
                folded.copy(event = event.copy(id = folded.event.id), collapsedCount = folded.collapsedCount + 1)
                    .also { records[NotificationInboxKey(normalizedRecipientId, folded.event.id, project)] = it }
            }
        }

    private fun findUnreadFolding(
        recipientId: String,
        collapseKey: String,
        project: String?,
    ): NotificationInboxRecord? =
        records.entries.asSequence()
            .filter { it.key.project == project && it.key.recipientId == recipientId }
            .map { it.value }
            .firstOrNull { it.readAt == null && it.event.collapseKey == collapseKey }

    override fun findByRecipient(
        recipientId: String,
        query: NotificationInboxQuery,
    ): NotificationInboxPage {
        val normalizedRecipientId = recipientId.trim()
        val project = project()
        val filtered = records.entries.asSequence()
            .filter { it.key.project == project && it.key.recipientId == normalizedRecipientId }
            .map { it.value }
            .filter { !query.unreadOnly || it.readAt == null }
            .filter { query.topic.isNullOrBlank() || it.event.topic == query.topic.trim() }
            .sortedWith(
                compareByDescending<NotificationInboxRecord> { it.event.createdAt }
                    .thenByDescending { it.event.id },
            )
            .toList()

        return NotificationInboxPage(
            values = filtered.drop(query.offset()).take(query.size),
            totalElements = filtered.size.toLong(),
        )
    }

    @Synchronized
    override fun markRead(
        recipientId: String,
        eventId: String,
        readAt: Instant,
    ): NotificationInboxRecord? {
        val key = NotificationInboxKey(recipientId.trim(), eventId.trim(), project())
        return records.computeIfPresent(key) { _, current ->
            current.copy(readAt = current.readAt ?: readAt)
        }
    }

    @Synchronized
    override fun markAllRead(
        recipientId: String,
        readAt: Instant,
    ): Int {
        val normalizedRecipientId = recipientId.trim()
        var updated = 0
        val project = project()
        records.keys
            .filter { it.project == project && it.recipientId == normalizedRecipientId }
            .forEach { key ->
                records.computeIfPresent(key) { _, current ->
                    if (current.readAt == null) {
                        updated += 1
                        current.copy(readAt = readAt)
                    } else {
                        current
                    }
                }
            }
        return updated
    }

    override fun deleteOlderThan(cutoff: Instant): Int = removeIf { it.event.createdAt.isBefore(cutoff) }

    override fun deleteByTopic(topic: String): Int = removeIf { it.event.topic == topic }

    override fun deleteByRecipientAndTopic(
        recipientId: String,
        topic: String,
    ): Int = removeIf { it.recipientId == recipientId.trim() && it.event.topic == topic }

    /** 지금 프로젝트의 줄만 지운다 - 정리는 프로젝트마다 따로 돈다 */
    @Synchronized
    private fun removeIf(predicate: (NotificationInboxRecord) -> Boolean): Int {
        val project = project()
        val doomed = records.entries.filter { it.key.project == project && predicate(it.value) }.map { it.key }
        doomed.forEach { records.remove(it) }
        return doomed.size
    }

    /** 프로젝트 모드: 같은 계정 id 가 프로젝트 사이에서 겹쳐도 받은편지함이 섞이지 않는다. 프로젝트 밖(한 서비스)이면 null */
    private data class NotificationInboxKey(
        val recipientId: String,
        val eventId: String,
        val project: String?,
    )

    private fun project(): String? = dev.sumin.skeleton.common.project.ProjectContext.currentOrNull()?.id
}

object NoopNotificationInboxRepository : NotificationInboxRepository {
    override fun save(
        event: NotificationEvent,
        recipientIds: Set<String>,
    ): List<NotificationInboxRecord> = emptyList()

    override fun findByRecipient(
        recipientId: String,
        query: NotificationInboxQuery,
    ): NotificationInboxPage = NotificationInboxPage(values = emptyList(), totalElements = 0)

    override fun markRead(
        recipientId: String,
        eventId: String,
        readAt: Instant,
    ): NotificationInboxRecord? = null

    override fun markAllRead(
        recipientId: String,
        readAt: Instant,
    ): Int = 0

    override fun deleteOlderThan(cutoff: Instant): Int = 0

    override fun deleteByTopic(topic: String): Int = 0

    override fun deleteByRecipientAndTopic(
        recipientId: String,
        topic: String,
    ): Int = 0
}
