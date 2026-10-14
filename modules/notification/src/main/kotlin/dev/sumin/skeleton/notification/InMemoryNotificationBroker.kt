package dev.sumin.skeleton.notification

import dev.sumin.skeleton.common.project.ProjectContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 프로젝트 모드: 구독은 **구독한 프로젝트**를 기억하고, 알림은 그 프로젝트에서 발행된 것만 받는다 — 계정 id 가 프로젝트 사이에 겹쳐도 알림이 새지 않는다.
 * 프로젝트 밖에서 구독한 쪽(서버 전체 리스너 — 슬랙 전달 등)은 모든 프로젝트의 알림을 듣는다. 프로젝트 없이 발행한 알림은 서버 전체 리스너만 듣는다.
 * 프로젝트가 없으면(한 서비스) 지금과 같다.
 */
class InMemoryNotificationBroker(
    private val inboxRepository: NotificationInboxRepository = NoopNotificationInboxRepository,
    private val recipientResolver: NotificationRecipientResolver = DefaultNotificationRecipientResolver(),
) : NotificationBroker {
    private val subscriptions = ConcurrentHashMap<String, BrokerSubscription>()

    override fun publish(event: NotificationEvent): NotificationPublishResult {
        inboxRepository.save(event, recipientResolver.resolveRecipients(event))
        var delivered = 0
        val project = ProjectContext.currentOrNull()?.id
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
        return NotificationPublishResult(eventId = event.id, deliveredSubscribers = delivered)
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
