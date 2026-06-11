package dev.sumin.skeleton.common.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("skeleton.config.validation")
data class ConfigValidationProperties(
    val enabled: Boolean = true,
    val failFast: Boolean = true,
    val dummyMarkers: Set<String> = setOf("change-me", "dummy", "dev-local", "local-secret"),
    val requirements: List<Requirement> = emptyList(),
) {
    data class Requirement(
        val property: String,
        val env: String? = null,
        val ssm: String? = null,
        val profiles: Set<String> = emptySet(),
        val allowDummyProfiles: Set<String> = emptySet(),
        val secret: Boolean = false,
        val description: String? = null,
    )
}
