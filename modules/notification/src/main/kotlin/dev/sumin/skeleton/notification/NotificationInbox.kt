package dev.sumin.skeleton.notification

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class NotificationInboxRecord(
    val recipientId: String,
    val event: NotificationEvent,
    val readAt: Instant? = null,
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

    override fun save(
        event: NotificationEvent,
        recipientIds: Set<String>,
    ): List<NotificationInboxRecord> =
        recipientIds.mapNotNull { recipientId ->
            val normalizedRecipientId = recipientId.trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val key = NotificationInboxKey(normalizedRecipientId, event.id, project())
            records.computeIfAbsent(key) {
                NotificationInboxRecord(recipientId = normalizedRecipientId, event = event)
            }
        }

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
}
