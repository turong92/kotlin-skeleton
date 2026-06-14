package dev.sumin.skeleton.common.logging

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("skeleton.redaction")
data class RedactionProperties(
    val replacement: String = "[REDACTED]",
    val additionalSensitiveNames: Set<String> = emptySet(),
)
