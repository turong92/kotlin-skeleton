package dev.sumin.skeleton.common.observability

data class ObservabilityLink(
    val id: String,
    val label: String,
    val url: String,
    val kind: ObservabilityLinkKind = ObservabilityLinkKind.CUSTOM,
)

enum class ObservabilityLinkKind {
    LOGS,
    TRACE,
    RUN,
    PROVIDER,
    CUSTOM,
}
