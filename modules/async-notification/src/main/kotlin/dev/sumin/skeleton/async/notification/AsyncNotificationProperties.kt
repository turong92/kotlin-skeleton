package dev.sumin.skeleton.async.notification

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("skeleton.async-notification")
data class AsyncNotificationProperties(
    val enabled: Boolean = true,
    val topic: String = "async.exception",
    val type: String = "async-exception",
    val title: String = "Async task failed",
)
