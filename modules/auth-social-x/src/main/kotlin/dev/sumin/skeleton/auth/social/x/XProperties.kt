package dev.sumin.skeleton.auth.social.x

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * X(Twitter) OAuth 2.0 — Authorization Code with PKCE, 기밀(confidential) 클라이언트. **client-id 가 비어 있으면 제공자는 없다** (켜는 스위치가 따로 없다).
 */
@ConfigurationProperties("skeleton.auth-social-x")
data class XProperties(
    /** X 개발자 콘솔 앱의 OAuth 2.0 Client ID */
    val clientId: String = "",
    /** 같은 앱의 Client Secret — 토큰 엔드포인트에서 HTTP Basic 으로 보낸다 (기밀 클라이언트) */
    val clientSecret: String = "",
    /** 프론트가 쓰는 redirect URI (콘솔의 Callback URI 와 글자 하나까지 같아야 한다) */
    val redirectUri: String? = null,
    /** true 면 `users.email` 스코프를 요청하고 `confirmed_email` 을 읽는다. 앱의 이메일 권한이 승인돼 있어야 값이 온다 — 없으면 주소 없는 계정 */
    val requestEmail: Boolean = false,
    /** true 면 `confirmed_email` 을 "확인된 이메일" 로 쳐 계정 병합에 쓴다. 문서가 확인 여부를 보증한다고 명시하지 않아 기본 false (확인 필요) */
    val trustConfirmedEmail: Boolean = false,
    /** API 호스트 (토큰 · users/me). 테스트 · 프록시용 */
    val apiBaseUrl: String = "https://api.x.com",
    val authorizeUrl: String = "https://x.com/i/oauth2/authorize",
)
