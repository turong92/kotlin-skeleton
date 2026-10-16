package dev.sumin.skeleton.notification.websocket

import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationSubscription
import dev.sumin.skeleton.notification.NotificationSubscriptionRegistry
import dev.sumin.skeleton.common.project.ProjectContext
import java.util.concurrent.atomic.AtomicBoolean
import org.springframework.context.SmartLifecycle
import org.springframework.messaging.simp.SimpMessagingTemplate

class NotificationWebSocketBridge(
    private val subscriptionRegistry: NotificationSubscriptionRegistry,
    private val messagingTemplate: SimpMessagingTemplate,
    private val destinationResolver: NotificationWebSocketDestinationResolver,
    private val properties: NotificationWebSocketProperties,
) : SmartLifecycle {
    private val running = AtomicBoolean(false)
    private var subscription: NotificationSubscription? = null

    override fun start() {
        if (running.compareAndSet(false, true)) {
            subscription = subscriptionRegistry.subscribe(properties.broker.bridgeTopics) { event ->
                forward(event)
            }
        }
    }

    override fun stop() {
        if (running.compareAndSet(true, false)) {
            subscription?.close()
            subscription = null
        }
    }

    override fun isRunning(): Boolean =
        running.get()

    /**
     * 프로젝트 모드: 알림을 발행한 프로젝트(지금 문맥)의 `project` 머리글을 달고(공개 토픽 구독자의 선택자가 거른다), 사용자 키에 프로젝트를 붙인다(세션의 사용자 이름과 같은 꼴).
     * 프로젝트 없이 발행한 알림은 머리글이 없어 어느 프로젝트의 공개 토픽 구독에도 가지 않는다.
     */
    private fun forward(event: NotificationEvent) {
        val project = ProjectContext.currentOrNull()?.id
        val headers = project?.let { mapOf<String, Any>(PROJECT_MESSAGE_HEADER to it) }
        destinationResolver.resolveDestinations(event).forEach { destination ->
            val user = destination.user
            when {
                user == null && headers == null -> messagingTemplate.convertAndSend(destination.destination, event)
                user == null -> messagingTemplate.convertAndSend(destination.destination, event, headers!!)
                headers == null -> messagingTemplate.convertAndSendToUser(user, destination.destination, event)
                else -> messagingTemplate.convertAndSendToUser(ProjectContext.scopedKey(user), destination.destination, event, headers)
            }
        }
    }
}
