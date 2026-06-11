package dev.sumin.skeleton.notification.websocket

import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationSubscription
import dev.sumin.skeleton.notification.NotificationSubscriptionRegistry
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

    private fun forward(event: NotificationEvent) {
        destinationResolver.resolveDestinations(event).forEach { destination ->
            if (destination.user == null) {
                messagingTemplate.convertAndSend(destination.destination, event)
            } else {
                messagingTemplate.convertAndSendToUser(destination.user, destination.destination, event)
            }
        }
    }
}
