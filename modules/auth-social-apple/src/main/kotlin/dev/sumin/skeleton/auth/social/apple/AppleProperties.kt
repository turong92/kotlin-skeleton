package dev.sumin.skeleton.auth.social.apple

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Sign in with Apple (iOS 앱) — 앱의 네이티브 SDK 가 준 identityToken / authorizationCode 를 서버가 검증한다. **client-id 가 비어 있으면 모듈은 꺼져 있다** (켜는 스위치가 따로 없다).
 * [toString] 은 개인키를 가린다.
 */
@ConfigurationProperties("skeleton.auth-social-apple")
data class AppleProperties(
    /** iOS 앱의 번들 id (예: `com.example.app`) — ID 토큰의 `aud` 이자 client_secret 의 `sub` */
    val clientId: String = "",
    /** 애플 개발자 계정의 Team ID (10자) — client_secret 의 `iss` */
    val teamId: String = "",
    /** Sign in with Apple 키의 Key ID (10자) — client_secret 헤더의 `kid` */
    val keyId: String = "",
    /** 그 키의 `.p8` 파일 내용(PKCS#8 PEM). 환경변수로만 넣는다 — 줄바꿈이 `\n` 두 글자로 와도 된다 */
    val privateKey: String = "",
    /** 애플 인증 서버 주소 — ID 토큰의 `iss`, client_secret 의 `aud`, `/auth/keys` · `/auth/token` · `/auth/revoke` 의 호스트. 시험 · 프록시용 */
    val baseUrl: String = "https://appleid.apple.com",
) {
    override fun toString() = "AppleProperties(clientId=$clientId, teamId=$teamId, keyId=$keyId, privateKey=<redacted>, baseUrl=$baseUrl)"
}
