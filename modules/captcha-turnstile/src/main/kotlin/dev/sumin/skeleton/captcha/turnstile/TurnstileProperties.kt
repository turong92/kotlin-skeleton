package dev.sumin.skeleton.captcha.turnstile

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * ```yaml
 * skeleton:
 *   captcha-turnstile:
 *     enabled: true
 *     secret-key: ${TURNSTILE_SECRET_KEY}
 *     expected-hostname: ovation.example.com   # 비우면 검사 안 함
 * ```
 * 기본 꺼짐. 꺼져 있으면 [TurnstileVerifier] 빈이 없다 — 앱은 `ObjectProvider` 로 받아 "없으면 통과" 로 처리한다.
 */
@ConfigurationProperties("skeleton.captcha-turnstile")
data class TurnstileProperties(
    val enabled: Boolean = false,
    val secretKey: String = "",
    val siteverifyUrl: String = "https://challenges.cloudflare.com/turnstile/v0/siteverify",
    val timeout: Duration = Duration.ofSeconds(5),
    /** 응답의 hostname 이 이 값과 다르면 실패 처리 */
    val expectedHostname: String = "",
    /** 응답의 action 이 이 값과 다르면 실패 처리 (위젯의 data-action) */
    val expectedAction: String = "",
)
