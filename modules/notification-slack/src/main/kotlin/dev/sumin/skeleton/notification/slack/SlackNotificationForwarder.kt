package dev.sumin.skeleton.notification.slack

import dev.sumin.skeleton.common.observability.ObservabilityContext
import dev.sumin.skeleton.common.observability.ObservabilityLinkResolver
import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationSeverity
import dev.sumin.skeleton.notification.NotificationSubscriber
import dev.sumin.skeleton.notification.NotificationSubscription
import dev.sumin.skeleton.notification.NotificationSubscriptionRegistry

class SlackNotificationForwarder(
    private val sender: SlackAlertSender,
    private val properties: SlackNotificationProperties,
    subscriptionRegistry: NotificationSubscriptionRegistry?,
    private val linkResolver: ObservabilityLinkResolver = ObservabilityLinkResolver { emptyList() },
) : AutoCloseable {
    private val subscription: NotificationSubscription? =
        if (properties.notificationEvents) {
            subscriptionRegistry?.subscribe(subscriber = NotificationSubscriber { event -> forward(event) })
        } else {
            null
        }

    private fun forward(event: NotificationEvent) {
        val fields = event.fields()
        val links = linkResolver.resolve(
            ObservabilityContext.fromMdc(
                fields + mapOf(
                    "topic" to event.topic,
                    "type" to event.type,
                    "route" to event.topic,
                ),
            ),
        )

        sender.send(
            SlackAlert(
                title = event.title ?: event.type,
                message = event.message ?: event.type,
                severity = event.severity.toSlackSeverity(),
                topic = event.topic,
                route = event.topic,
                fields = fields,
                trace = SlackTraceContexts.current(),
                links = links,
                occurredAt = event.createdAt,
            ),
        )
    }

    override fun close() {
        subscription?.close()
    }

    private fun NotificationSeverity.toSlackSeverity(): SlackAlertSeverity =
        when (this) {
            NotificationSeverity.INFO -> SlackAlertSeverity.INFO
            NotificationSeverity.SUCCESS -> SlackAlertSeverity.SUCCESS
            NotificationSeverity.WARNING -> SlackAlertSeverity.WARNING
            NotificationSeverity.ERROR -> SlackAlertSeverity.ERROR
        }

    private fun NotificationEvent.fields(): Map<String, String?> =
        buildMap {
            put("id", id)
            put("type", type)
            payload.forEach { (name, value) ->
                put(name, value?.toString())
            }
        }
}
