package dev.sumin.skeleton.notification.sse

import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationSubscription
import dev.sumin.skeleton.notification.NotificationSubscriptionRegistry
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

class NotificationSseService(
    private val subscriptionRegistry: NotificationSubscriptionRegistry,
    private val properties: NotificationSseProperties,
) {
    fun connect(topics: Collection<String>): SseEmitter {
        val normalizedTopics = topics.mapNotNullTo(linkedSetOf()) { topic ->
            topic.trim().takeIf { it.isNotBlank() }
        }
        val subscriptionRef = AtomicReference<NotificationSubscription>()
        val emitter = SubscriptionClosingSseEmitter(properties.timeout.toMillis()) {
            subscriptionRef.get()?.close()
        }
        val subscription = subscriptionRegistry.subscribe(normalizedTopics) { event ->
            emitter.sendNotification(event)
        }
        subscriptionRef.set(subscription)

        emitter.onCompletion { emitter.closeSubscription() }
        emitter.onTimeout {
            emitter.closeSubscription()
            emitter.complete()
        }
        emitter.onError { _: Throwable -> emitter.closeSubscription() }

        emitter.sendConnectedEvent(normalizedTopics)
        return emitter
    }

    private fun SseEmitter.sendNotification(event: NotificationEvent) {
        try {
            send(
                SseEmitter.event()
                    .id(event.id)
                    .name(event.type)
                    .data(event),
            )
        } catch (ex: Exception) {
            completeWithError(ex)
        }
    }

    private fun SseEmitter.sendConnectedEvent(topics: Set<String>) {
        try {
            send(
                SseEmitter.event()
                    .name("connected")
                    .data(NotificationSseConnectedEvent(topics = topics)),
            )
        } catch (ex: Exception) {
            completeWithError(ex)
        }
    }
}

data class NotificationSseConnectedEvent(
    val topics: Set<String>,
    val connectedAt: Instant = Instant.now(),
)

private class SubscriptionClosingSseEmitter(
    timeout: Long,
    private val onClose: () -> Unit,
) : SseEmitter(timeout) {
    private val closed = AtomicBoolean(false)

    fun closeSubscription() {
        if (closed.compareAndSet(false, true)) {
            onClose()
        }
    }

    override fun complete() {
        closeSubscription()
        super.complete()
    }

    override fun completeWithError(ex: Throwable) {
        closeSubscription()
        super.completeWithError(ex)
    }
}
