package dev.sumin.skeleton.notification.web

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("skeleton.notification.inbox")
data class NotificationInboxProperties(
    /** false 면 받은편지함 엔드포인트를 등록하지 않는다 (앱이 자기 컨트롤러를 둘 때) */
    val enabled: Boolean = true,
)
