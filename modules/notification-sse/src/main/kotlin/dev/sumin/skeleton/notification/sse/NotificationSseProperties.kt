package dev.sumin.skeleton.notification.sse

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("skeleton.notification.sse")
data class NotificationSseProperties(
    val enabled: Boolean = true,
    val timeout: Duration = Duration.ofMinutes(30),
    val publicEndpoint: Boolean = false,
)
