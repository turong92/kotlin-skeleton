package dev.sumin.skeleton.notification.slack

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import dev.sumin.skeleton.common.logging.SensitiveValueRedactor
import dev.sumin.skeleton.common.observability.ObservabilityLink

class SlackAlertMessageFactory(
    private val properties: SlackNotificationProperties,
    private val redactor: SensitiveValueRedactor = SensitiveValueRedactor(),
) {
    fun create(
        alert: SlackAlert,
        route: SlackNotificationProperties.Route = properties.route(alert.route ?: alert.topic),
    ): SlackWebhookPayload {
        val displayTopic = route.topic ?: alert.topic
        val fields = linkedMapOf<String, String?>(
            "severity" to alert.severity.name,
            "topic" to displayTopic,
        )
        fields.putAll(redactor.redact(alert.fields))
        fields.putAll(
            mapOf(
                "traceId" to alert.trace.traceId,
                "spanId" to alert.trace.spanId,
                "parentSpanId" to alert.trace.parentSpanId,
            ),
        )

        val blocks = mutableListOf(
            SlackBlock(
                type = "section",
                text = SlackText(
                    text = "*[${alert.severity.name}] ${escape(alert.title)}*\n${escape(alert.message)}",
                ),
            ),
            SlackBlock(
                type = "section",
                fields = fields
                    .filterValues { !it.isNullOrBlank() }
                    .map { (name, value) -> SlackText(text = "*${escape(name)}*\n${escape(value.orEmpty())}") },
            ),
        )
        alert.links.takeIf { it.isNotEmpty() }?.let { links ->
            blocks += SlackBlock(
                type = "context",
                elements = listOf(SlackText(text = "Links: ${links.joinToString(" · ") { it.toSlackLink() }}")),
            )
        }
        blocks += SlackBlock(
            type = "context",
            elements = listOf(SlackText(text = "occurredAt=${alert.occurredAt}")),
        )

        return SlackWebhookPayload(
            text = "[${alert.severity.name}] ${alert.title}",
            username = route.username,
            iconEmoji = route.iconEmoji,
            blocks = blocks,
        )
    }

    private fun ObservabilityLink.toSlackLink(): String =
        "<${escape(url)}|${escape(label)}>"

    private fun escape(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
}

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class SlackWebhookPayload(
    val text: String,
    val username: String? = null,
    @JsonProperty("icon_emoji")
    val iconEmoji: String? = null,
    val blocks: List<SlackBlock> = emptyList(),
)

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class SlackBlock(
    val type: String,
    val text: SlackText? = null,
    val fields: List<SlackText> = emptyList(),
    val elements: List<SlackText> = emptyList(),
)

data class SlackText(
    val type: String = "mrkdwn",
    val text: String,
)
