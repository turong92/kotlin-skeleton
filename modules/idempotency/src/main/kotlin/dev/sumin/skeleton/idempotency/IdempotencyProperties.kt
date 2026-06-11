package dev.sumin.skeleton.idempotency

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "skeleton.idempotency")
data class IdempotencyProperties(
    val enabled: Boolean = true,
    val ttl: Duration = Duration.ofHours(24),
    val cachedMethods: Set<String> = setOf("POST", "PUT", "PATCH", "DELETE"),
)
