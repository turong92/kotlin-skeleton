package dev.sumin.skeleton.common.observability

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class DefaultObservabilityLinkResolver(
    private val properties: ObservabilityLinkProperties,
) : ObservabilityLinkResolver {
    private val placeholderTokenRegex = Regex("\\{([^{}]+)}")
    private val placeholderNameRegex = Regex("[A-Za-z][A-Za-z0-9]*")
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
            val placeholderTokens = placeholderTokens(template.url, id)
            val malformedPlaceholders = placeholderTokens.filterNot { placeholderNameRegex.matches(it) }.toSet()
            check(malformedPlaceholders.isEmpty()) {
                "Malformed observability link placeholders for template '$id': " +
                    "${malformedPlaceholders.joinToString(", ")}. " +
                    "Placeholder names must match ${placeholderNameRegex.pattern}"
            }

            val fields = placeholderTokens + template.requiredFields
            val unknownFields = fields.filterNot { it in allowedFields }.toSet()
            check(unknownFields.isEmpty()) {
                "Unknown observability link fields for template '$id': ${unknownFields.joinToString(", ")}"
            }
        }
    }

    private fun placeholders(template: String): Set<String> =
        placeholderTokens(template).filter { placeholderNameRegex.matches(it) }.toSet()

    private fun placeholderTokens(
        template: String,
        templateId: String? = null,
    ): Set<String> {
        val tokens = mutableSetOf<String>()
        var index = 0
        while (index < template.length) {
            when (template[index]) {
                '{' -> {
                    val closeIndex = template.indexOf('}', startIndex = index + 1)
                    check(closeIndex >= 0) {
                        malformedPlaceholderMessage(templateId, template.substring(index))
                    }

                    val token = template.substring(index + 1, closeIndex)
                    check(token.isNotEmpty()) {
                        malformedPlaceholderMessage(templateId, "{}")
                    }

                    tokens += token
                    index = closeIndex + 1
                }
                '}' -> error(malformedPlaceholderMessage(templateId, "}"))
                else -> index += 1
            }
        }
        return tokens
    }

    private fun malformedPlaceholderMessage(
        templateId: String?,
        placeholder: String,
    ): String =
        buildString {
            append("Malformed observability link placeholders")
            if (templateId != null) append(" for template '$templateId'")
            append(": ")
            append(placeholder)
            append(". Placeholder names must match ")
            append(placeholderNameRegex.pattern)
        }

    private fun expand(
        template: String,
        context: ObservabilityContext,
    ): String =
        placeholderTokenRegex.replace(template) { match ->
            encode(context.value(match.groupValues[1]).orEmpty())
        }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
}
