package dev.sumin.skeleton.common.observability

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class DefaultObservabilityLinkResolver(
    private val properties: ObservabilityLinkProperties,
) : ObservabilityLinkResolver {
    private val placeholderRegex = Regex("\\{([A-Za-z][A-Za-z0-9]*)}")
    private val allowedFields = ObservabilityContext.knownFields + properties.customFields

    init {
        if (properties.enabled) validateTemplates()
    }

    override fun resolve(context: ObservabilityContext): List<ObservabilityLink> {
        if (!properties.enabled) return emptyList()

        return properties.templates.mapNotNull { (id, template) ->
            if (template.label.isBlank() || template.url.isBlank()) return@mapNotNull null
            if (template.requiredFields.any { field -> context.value(field).isNullOrBlank() }) return@mapNotNull null

            val placeholders = placeholders(template.url)
            if (placeholders.any { field -> context.value(field).isNullOrBlank() }) return@mapNotNull null

            ObservabilityLink(
                id = id,
                label = template.label,
                url = expand(template.url, context),
                kind = template.kind,
            )
        }
    }

    private fun validateTemplates() {
        properties.templates.forEach { (id, template) ->
            val fields = placeholders(template.url) + template.requiredFields
            val unknownFields = fields.filterNot { it in allowedFields }.toSet()
            check(unknownFields.isEmpty()) {
                "Unknown observability link fields for template '$id': ${unknownFields.joinToString(", ")}"
            }
        }
    }

    private fun placeholders(template: String): Set<String> =
        placeholderRegex.findAll(template).map { match -> match.groupValues[1] }.toSet()

    private fun expand(
        template: String,
        context: ObservabilityContext,
    ): String =
        placeholderRegex.replace(template) { match ->
            encode(context.value(match.groupValues[1]).orEmpty())
        }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
}
