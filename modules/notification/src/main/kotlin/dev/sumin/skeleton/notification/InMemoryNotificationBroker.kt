package dev.sumin.skeleton.notification

import dev.sumin.skeleton.common.project.ProjectContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 프로젝트 모드: 구독은 **구독한 프로젝트**를 기억하고, 알림은 그 프로젝트에서 발행된 것만 받는다 — 계정 id 가 프로젝트 사이에 겹쳐도 알림이 새지 않는다.
 * 프로젝트 밖에서 구독한 쪽(서버 전체 리스너 — 슬랙 전달 등)은 모든 프로젝트의 알림을 듣는다. 프로젝트 없이 발행한 알림은 서버 전체 리스너만 듣는다.
 * 프로젝트가 없으면(한 서비스) 지금과 같다.
 *
 * 받은편지함 저장은 호출자 트랜잭션 안에서 바로 한다(롤백되면 알림도 없다, 실패는 호출자에게 던져진다).
 * 구독자 전달은 트랜잭션 동기화가 켜져 있으면 **커밋 뒤**로 미룬다 - 롤백된 알림이 SSE 로 새지 않게. 트랜잭션 밖이거나 `spring-tx` 가 없으면 바로 전달한다.
 * 미뤄진 발행의 [NotificationPublishResult.deliveredSubscribers] 는 0 이다(아직 전달하지 않았다).
 * 알림 실패가 업무를 실패시키면 안 되는 호출자는 커밋 뒤에 부르고 잡는다.
 *
 * 끌 수 있는 종류([NotificationPreferencesProperties])이면 받는 사람에서 그 종류를 끈 사람을 뺀다. 받는 사람이 정해진 알림(`recipientIds` 가 비어 있지 않음)은
 * 전달하는 이벤트도 남은 사람으로 줄이고, **받는 사람을 모두 끈 알림은 저장도 전달도 하지 않는다**(빈 `recipientIds` 는 전체 공지라 그대로 넘기면 모두에게 간다).
 * 받는 사람이 없는 전체 공지는 끌 수 없다.
 */
class InMemoryNotificationBroker(
    private val inboxRepository: NotificationInboxRepository = NoopNotificationInboxRepository,
    private val recipientResolver: NotificationRecipientResolver = DefaultNotificationRecipientResolver(),
    private val muteRepository: NotificationMuteRepository = InMemoryNotificationMuteRepository(),
    /** 사람이 끌 수 있는 종류 ([NotificationPreferencesProperties]). 비면 끄기를 조회하지 않는다 */
    mutableTypes: Collection<String> = emptyList(),
) : NotificationBroker {
    private val mutableTypes: Set<String> = mutableTypes.toSet()
    private val subscriptions = ConcurrentHashMap<String, BrokerSubscription>()

    override fun publish(event: NotificationEvent): NotificationPublishResult {
        val recipients = recipientResolver.resolveRecipients(event)
        val muted = mutedAmong(event, recipients)
        val remaining = recipients - muted
        if (muted.isNotEmpty() && remaining.isEmpty()) {
            return NotificationPublishResult(eventId = event.id, deliveredSubscribers = 0)
        }
        val effective = if (muted.isEmpty() || event.recipientIds.isEmpty()) event else event.withoutMuted(muted, remaining)
        inboxRepository.save(effective, remaining)
        val project = ProjectContext.currentOrNull()?.id
        if (AfterCommit.runAfterCommit { deliver(effective, project) }) {
            return NotificationPublishResult(eventId = event.id, deliveredSubscribers = 0)
        }
        return NotificationPublishResult(eventId = event.id, deliveredSubscribers = deliver(effective, project))
    }

    private fun mutedAmong(
        event: NotificationEvent,
        recipients: Set<String>,
    ): Set<String> =
        if (event.type in mutableTypes && recipients.isNotEmpty()) muteRepository.mutedAmong(event.type, recipients) else emptySet()

    /**
     * 끈 사람을 뺀 이벤트. 뺀 뒤 `recipientIds` 가 비면 전체 공지로 읽히므로, 그때는 남은 받는 사람(payload 에서 해석된 사람)으로 채운다 -
     * 끈 사람이 있는 알림이 모두에게 새지 않는다.
     */
    private fun NotificationEvent.withoutMuted(
        muted: Set<String>,
        remaining: Set<String>,
    ): NotificationEvent = copy(recipientIds = (recipientIds - muted).ifEmpty { remaining })

    private fun deliver(
        event: NotificationEvent,
        project: String?,
    ): Int {
        var delivered = 0
        subscriptions.values.forEach { subscription ->
            if (subscription.matches(event.topic) && subscription.hears(project)) {
                runCatching {
                    subscription.subscriber.onNotification(event)
                }.onSuccess {
                    delivered += 1
                }.onFailure {
                    subscription.close()
                }
            }
        }
        return delivered
    }

    override fun subscribe(
        topics: Set<String>,
        subscriber: NotificationSubscriber,
    ): NotificationSubscription {
        val normalizedTopics = topics.mapNotNullTo(linkedSetOf()) { topic ->
            topic.trim().takeIf { it.isNotBlank() }
        }
        val subscription = BrokerSubscription(
            id = UUID.randomUUID().toString(),
            topics = normalizedTopics,
            subscriber = subscriber,
            project = ProjectContext.currentOrNull()?.id,
            onClose = { id -> subscriptions.remove(id) },
        )
        subscriptions[subscription.id] = subscription
        return subscription
    }

    private class BrokerSubscription(
        override val id: String,
        override val topics: Set<String>,
        val subscriber: NotificationSubscriber,
        /** 구독한 프로젝트 — null 이면 서버 전체 리스너(한 서비스, 또는 프로젝트 밖에서 한 구독) */
        private val project: String?,
        private val onClose: (String) -> Unit,
    ) : NotificationSubscription {
        private val closed = AtomicBoolean(false)

        fun matches(topic: String): Boolean =
            !closed.get() && (topics.isEmpty() || topic in topics)

        /** 서버 전체 리스너는 모든 프로젝트를, 프로젝트의 구독은 자기 프로젝트의 알림만 */
        fun hears(publishedIn: String?): Boolean = project == null || project == publishedIn

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                onClose(id)
            }
        }
    }
}
