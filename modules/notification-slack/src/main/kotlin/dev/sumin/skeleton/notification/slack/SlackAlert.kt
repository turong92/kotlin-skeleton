package dev.sumin.skeleton.notification.slack

import dev.sumin.skeleton.common.observability.ObservabilityLink
import java.time.Instant

data class SlackAlert(
    val title: String,
    val message: String,
    val severity: SlackAlertSeverity = SlackAlertSeverity.ERROR,
    val topic: String = "operations",
    val route: String? = null,
    val fields: Map<String, String?> = emptyMap(),
    val trace: SlackTraceContext = SlackTraceContext.empty(),
    val occurredAt: Instant = Instant.now(),
    val links: List<ObservabilityLink> = emptyList(),
)

enum class SlackAlertSeverity {
    INFO,
    SUCCESS,
    WARNING,
    ERROR,
}

data class SlackTraceContext(
    val traceId: String? = null,
    val spanId: String? = null,
    val parentSpanId: String? = null,
) {
    companion object {
        fun empty(): SlackTraceContext = SlackTraceContext()
    }
}
