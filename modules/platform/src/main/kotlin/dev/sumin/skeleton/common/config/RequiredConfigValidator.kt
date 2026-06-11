package dev.sumin.skeleton.common.config

class ConfigValidationException(message: String) : IllegalStateException(message)

fun interface PropertyResolver {
    fun getProperty(name: String): String?
}

class MapPropertyResolver(
    private val values: Map<String, String?>,
) : PropertyResolver {
    override fun getProperty(name: String): String? = values[name]
}

object RequiredConfigValidator {
    fun validate(
        activeProfiles: Set<String>,
        properties: ConfigValidationProperties,
        propertyResolver: PropertyResolver,
    ) {
        if (!properties.enabled) return

        val profiles = activeProfiles.ifEmpty { setOf("default") }
        val issues = properties.requirements
            .filter { requirement -> requirement.appliesTo(profiles) }
            .flatMap { requirement -> validateRequirement(requirement, profiles, properties, propertyResolver) }

        if (issues.isNotEmpty() && properties.failFast) {
            throw ConfigValidationException(formatIssues(issues))
        }
    }

    private fun validateRequirement(
        requirement: ConfigValidationProperties.Requirement,
        activeProfiles: Set<String>,
        properties: ConfigValidationProperties,
        propertyResolver: PropertyResolver,
    ): List<ConfigIssue> {
        val value = propertyResolver.getProperty(requirement.property)
        if (value.isNullOrBlank()) {
            return activeProfiles.map { profile ->
                ConfigIssue(
                    property = requirement.property,
                    reason = "missing",
                    env = requirement.env,
                    ssm = requirement.ssm?.replace("{profile}", profile),
                    description = requirement.description,
                )
            }
        }

        val dummyAllowed = activeProfiles.any { profile -> profile in requirement.allowDummyProfiles }
        val dummy = requirement.secret && !dummyAllowed && properties.dummyMarkers.any { marker ->
            value.contains(marker, ignoreCase = true)
        }
        if (!dummy) return emptyList()

        return activeProfiles.map { profile ->
            ConfigIssue(
                property = requirement.property,
                reason = "dummy value is not allowed for profile '$profile'",
                env = requirement.env,
                ssm = requirement.ssm?.replace("{profile}", profile),
                description = requirement.description,
            )
        }
    }

    private fun ConfigValidationProperties.Requirement.appliesTo(activeProfiles: Set<String>): Boolean =
        profiles.isEmpty() || activeProfiles.any { profile -> profile in profiles }

    private fun formatIssues(issues: List<ConfigIssue>): String =
        buildString {
            appendLine("Configuration startup failed.")
            appendLine()
            appendLine("Missing or invalid required config:")
            issues.forEach { issue ->
                append("- ")
                append(issue.property)
                append(" (")
                append(issue.reason)
                appendLine(")")
                issue.description?.takeIf { it.isNotBlank() }?.let { description ->
                    append("  description: ")
                    appendLine(description)
                }
                if (!issue.env.isNullOrBlank()) {
                    append("  env: ")
                    appendLine(issue.env)
                }
                if (!issue.ssm.isNullOrBlank()) {
                    append("  ssm: ")
                    appendLine(issue.ssm)
                }
            }
        }.trimEnd()

    private data class ConfigIssue(
        val property: String,
        val reason: String,
        val env: String?,
        val ssm: String?,
        val description: String?,
    )
}
