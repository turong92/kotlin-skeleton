package dev.sumin.skeleton.notification.mail

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * SMTP 서버 자체는 Spring 표준 `spring.mail.*` (host, port, username, password, properties.mail.smtp.*) 로 설정한다.
 * 이 모듈은 발신자와 on/off 만 더한다.
 *
 * ```yaml
 * spring:
 *   mail:
 *     host: smtp.example.com
 *     port: 587
 *     username: ${SMTP_USERNAME}
 *     password: ${SMTP_PASSWORD}
 *     properties.mail.smtp.starttls.enable: true
 * skeleton:
 *   notification-mail:
 *     enabled: true
 *     from: "Ovation <no-reply@example.com>"
 * ```
 * 기본은 꺼짐(enabled=false) — 비밀이 없으면 통합이 죽는 대신 빠지는 스켈레톤 규칙.
 */
@ConfigurationProperties("skeleton.notification-mail")
data class MailProperties(
    val enabled: Boolean = false,
    val from: String = "",
)
