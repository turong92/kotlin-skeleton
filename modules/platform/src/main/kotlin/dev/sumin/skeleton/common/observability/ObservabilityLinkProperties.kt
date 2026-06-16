package dev.sumin.skeleton.common.observability

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("skeleton.observability.links")
data class ObservabilityLinkProperties(
    val enabled: Boolean = false,
    val customFields: Set<String> = emptySet(),
    val templates: Map<String, Template> = emptyMap(),
) {
    data class Template(
        val label: String = "",
        val kind: ObservabilityLinkKind = ObservabilityLinkKind.CUSTOM,
        val url: String = "",
        val requiredFields: List<String> = emptyList(),
    )
}
