package dev.sumin.skeleton.workspace

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * ```yaml
 * skeleton:
 *   workspace:
 *     http:
 *       enabled: true                  # false 면 사용자 경로(/api/v1/workspaces …)를 닫는다. 경계(CurrentWorkspace)는 그대로 쓸 수 있다
 *       base-path: /api/v1/workspaces
 *     invitation:
 *       enabled: false                 # true 면 초대(메일 링크). AccountFacts 빈(modules:account)과 mail.link-base-url 이 필요하다
 *       ttl: 7d
 *       resend-interval: 60s
 *       max-pending: 100
 *       max-per-request: 20
 *       base-path: /api/v1/workspace-invitations   # 미리보기 · 수락
 *     mail:
 *       link-base-url: https://app.example.com     # 메일 링크 = <이 주소>/invite?token=…
 *       default-locale: en
 *       log-links: AUTO                # 메일 모듈이 없을 때 링크(토큰 포함)를 로그에 남기나: AUTO = 보호 환경(stage · prod)이 아닐 때만
 * ```
 */
@ConfigurationProperties("skeleton.workspace")
data class WorkspaceProperties(
    val http: Http = Http(),
    val invitation: Invitation = Invitation(),
    val mail: Mail = Mail(),
) {
    data class Http(
        val enabled: Boolean = true,
        val basePath: String = "/api/v1/workspaces",
    )

    /** 안전 장치 기본값(업계 관행: GitHub 7일) — 앱 yml 에서 덮어쓴다. [enabled] 는 기본 꺼짐: 켜려면 AccountFacts 와 링크 주소가 있어야 한다 */
    data class Invitation(
        val enabled: Boolean = false,
        val ttl: Duration = Duration.ofDays(7),
        val resendInterval: Duration = Duration.ofSeconds(60),
        val maxPending: Int = 100,
        val maxPerRequest: Int = 20,
        val basePath: String = "/api/v1/workspace-invitations",
    )

    data class Mail(
        val linkBaseUrl: String = "",
        val defaultLocale: String = "en",
        val logLinks: LogLinks = LogLinks.AUTO,
    ) {
        enum class LogLinks { AUTO, ON, OFF }
    }
}
