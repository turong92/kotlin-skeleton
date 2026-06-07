package dev.sumin.skeleton.common.openapi

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "skeleton.openapi")
data class OpenApiProperties(
    val title: String = "kotlin-skeleton API",
    val version: String = "0.1.0",
    val description: String = "Composable Kotlin backend skeleton API",
)
