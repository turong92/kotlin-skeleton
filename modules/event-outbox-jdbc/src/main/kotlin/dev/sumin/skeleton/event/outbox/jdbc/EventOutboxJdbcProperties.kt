package dev.sumin.skeleton.event.outbox.jdbc

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("skeleton.event-outbox-jdbc")
data class EventOutboxJdbcProperties(
    val enabled: Boolean = true,
    val batchSize: Int = 100,
    val retryBackoff: Duration = Duration.ofSeconds(30),
)
