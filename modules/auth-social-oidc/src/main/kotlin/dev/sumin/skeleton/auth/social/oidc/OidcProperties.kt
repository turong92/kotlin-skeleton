package dev.sumin.skeleton.auth.social.oidc

import dev.sumin.skeleton.auth.social.oauth.NonceMode
import dev.sumin.skeleton.auth.social.oauth.PkceMode
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 범용 OpenID Connect 제공자들 — 키는 제공자 코드(`line`, `microsoft` …)이고 그 코드가 로그인 경로 `/auth/social/{코드}/login` 과 로그인 수단 이름이 된다.
 * **client-id 가 비어 있으면 그 제공자는 없는 것이다** (아무것도 만들지 않고 네트워크도 부르지 않는다). 칸마다 비워 두면 프리셋(`line`) 또는 일반 기본값이 채운다.
 */
@ConfigurationProperties("skeleton.auth-social-oidc")
data class OidcProperties(
    val providers: Map<String, Provider> = emptyMap(),
    /** discovery 문서를 다시 받기 전까지 */
    val discoveryCacheTtl: Duration = Duration.ofHours(24),
    /** JWKS 를 다시 받기 전까지 */
    val jwksCacheTtl: Duration = Duration.ofHours(1),
    /** 모르는 `kid` 때문에 JWKS 를 다시 받는 일의 최소 간격 (키 교체는 따라가되 가짜 kid 로 제공자를 두드리지 못하게) */
    val jwksRefreshCooldown: Duration = Duration.ofSeconds(30),
    /** `exp` · `nbf` 비교에서 봐주는 시계 어긋남 */
    val clockSkew: Duration = Duration.ofSeconds(60),
) {
    enum class ClientAuth { BASIC, POST, NONE }

    enum class UserinfoMode { NEVER, FALLBACK, ALWAYS }

    /** 제공자의 이메일을 "확인됨" 으로 칠 근거. CLAIM = `email_verified` 클레임이 true, NEVER = 항상 아님, ALWAYS = 제공자가 보증한다고 문서가 말할 때만 */
    enum class EmailTrust { CLAIM, NEVER, ALWAYS }

    data class Claims(
        val subject: String? = null,
        val email: String? = null,
        val emailVerified: String? = null,
        val name: String? = null,
        val picture: String? = null,
    )

    data class Provider(
        /** 프리셋 이름 (현재 `line`). 비우면 제공자 코드가 프리셋 이름과 같을 때 그것 */
        val preset: String? = null,
        /** discovery 로 엔드포인트를 읽는다: `<issuer>/.well-known/openid-configuration` */
        val issuer: String? = null,
        val authorizationEndpoint: String? = null,
        val tokenEndpoint: String? = null,
        val userinfoEndpoint: String? = null,
        val jwksUri: String? = null,
        val clientId: String = "",
        val clientSecret: String = "",
        val redirectUri: String? = null,
        val scopes: List<String>? = null,
        val pkce: PkceMode? = null,
        val nonce: NonceMode? = null,
        val tokenEndpointAuth: ClientAuth? = null,
        /** 받아들일 ID 토큰 서명 알고리즘 (RS256 · ES256 · HS256 …). HS* 는 client secret 으로 검증한다 */
        val idTokenAlgorithms: List<String>? = null,
        val userinfo: UserinfoMode? = null,
        val emailTrust: EmailTrust? = null,
        val claims: Claims = Claims(),
        /** 인가 URL 에 그대로 붙일 고정 파라미터 (`response_type=code` 는 늘 들어간다) */
        val authorizeParams: Map<String, String>? = null,
    )
}
