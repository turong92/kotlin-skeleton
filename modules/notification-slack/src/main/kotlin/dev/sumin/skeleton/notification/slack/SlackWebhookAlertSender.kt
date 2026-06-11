package dev.sumin.skeleton.notification.slack

import dev.sumin.skeleton.common.http.ExternalHttpClient
import org.slf4j.LoggerFactory

class SlackWebhookAlertSender(
    private val httpClient: ExternalHttpClient,
    private val properties: SlackNotificationProperties,
    private val messageFactory: SlackAlertMessageFactory,
) : SlackAlertSender {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(alert: SlackAlert) {
        if (!properties.enabled) return

        val route = properties.route(alert.route ?: alert.topic)
        if (!properties.shouldSend(alert.severity, route)) return

        val webhookUrl = route.webhookUrl?.takeIf { it.isNotBlank() } ?: run {
            log.warn("Slack alert skipped because webhookUrl is not configured")
            return
        }
        val payload = messageFactory.create(alert, route)

        val request = httpClient.post(
            clientName = properties.clientName,
            path = webhookUrl,
            body = payload,
            responseType = String::class.java,
        ) {
            timeout(properties.timeout)
            loggingTag("slack-alert")
        }
        val delivery = if (properties.retryAttempts > 0) {
            request.retry(properties.retryAttempts.toLong())
        } else {
            request
        }

        delivery.subscribe(
            { },
            { error -> log.warn("Slack alert delivery failed: {}", error.message) },
        )
    }
}
