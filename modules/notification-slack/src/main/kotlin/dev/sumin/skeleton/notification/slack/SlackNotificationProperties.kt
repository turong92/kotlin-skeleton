package dev.sumin.skeleton.notification.slack

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("skeleton.notification.slack")
data class SlackNotificationProperties(
    val enabled: Boolean = false,
    val clientName: String = "slack",
    val webhookUrl: String? = null,
    val defaultTopic: String = "operations",
    val username: String? = null,
    val iconEmoji: String? = null,
    val minimumSeverity: SlackAlertSeverity = SlackAlertSeverity.WARNING,
    val timeout: Duration = Duration.ofSeconds(2),
    val retryAttempts: Int = 1,
    val routes: Map<String, Route> = emptyMap(),
    val notificationEvents: Boolean = true,
    val exceptionAlerts: Boolean = true,
) {
    fun route(name: String?): Route {
        val configured = name?.trim()?.takeIf { it.isNotBlank() }?.let(routes::get)
        return Route(
            webhookUrl = configured?.webhookUrl ?: webhookUrl,
            topic = configured?.topic ?: defaultTopic,
            minimumSeverity = configured?.minimumSeverity,
            username = configured?.username ?: username,
            iconEmoji = configured?.iconEmoji ?: iconEmoji,
        )
    }

    fun shouldSend(
        severity: SlackAlertSeverity,
        route: Route = route(null),
    ): Boolean =
        severity.ordinal >= (route.minimumSeverity ?: minimumSeverity).ordinal

    data class Route(
        val webhookUrl: String? = null,
        val topic: String? = null,
        val minimumSeverity: SlackAlertSeverity? = null,
        val username: String? = null,
        val iconEmoji: String? = null,
    )
}
