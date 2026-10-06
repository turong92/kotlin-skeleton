package dev.sumin.skeleton.auth.social.oidc

import com.nimbusds.jose.JWSAlgorithm
import dev.sumin.skeleton.auth.social.oauth.NonceMode
import dev.sumin.skeleton.auth.social.oauth.PkceMode

/** 칸을 다 채운 제공자 설정 — 비워 둔 칸은 프리셋 → 일반 기본값 순으로 채워졌다 */
data class OidcResolved(
    val code: String,
    val issuer: String?,
    val authorizationEndpoint: String?,
    val tokenEndpoint: String?,
    val userinfoEndpoint: String?,
    val jwksUri: String?,
    val clientId: String,
    val clientSecret: String,
    val redirectUri: String?,
    val scopes: List<String>,
    val pkce: PkceMode,
    val nonce: NonceMode,
    val clientAuth: OidcProperties.ClientAuth,
    val algorithms: List<JWSAlgorithm>,
    val userinfo: OidcProperties.UserinfoMode,
    val emailTrust: OidcProperties.EmailTrust,
    val claims: Claims,
    val authorizeParams: Map<String, String>,
) {
    data class Claims(val subject: String, val email: String, val emailVerified: String, val name: String, val picture: String)
}

object OidcPresets {
    private val GENERIC = OidcProperties.Provider(
        scopes = listOf("openid", "profile", "email"),
        pkce = PkceMode.SUPPORTED,
        nonce = NonceMode.SUPPORTED,
        tokenEndpointAuth = OidcProperties.ClientAuth.BASIC,
        idTokenAlgorithms = listOf("RS256"),
        userinfo = OidcProperties.UserinfoMode.FALLBACK,
        emailTrust = OidcProperties.EmailTrust.CLAIM,
        authorizeParams = emptyMap(),
    )

    /**
     * LINE Login v2.1 (https://developers.line.biz/en/docs/line-login/integrate-line-login/ · https://developers.line.biz/en/reference/line-login/ · https://developers.line.biz/en/docs/line-login/verify-id-token/).
     * 웹 로그인의 ID 토큰은 HS256(채널 시크릿), 네이티브 · LIFF 는 ES256(JWKS) — 둘 다 받는다. email 스코프는 콘솔의 이메일 권한 승인이 있어야 하므로 기본 스코프에 없다.
     */
    private val LINE = GENERIC.copy(
        issuer = "https://access.line.me",
        authorizationEndpoint = "https://access.line.me/oauth2/v2.1/authorize",
        tokenEndpoint = "https://api.line.me/oauth2/v2.1/token",
        jwksUri = "https://api.line.me/oauth2/v2.1/certs",
        userinfoEndpoint = "https://api.line.me/oauth2/v2.1/userinfo",
        scopes = listOf("openid", "profile"),
        pkce = PkceMode.REQUIRED,
        nonce = NonceMode.REQUIRED,
        tokenEndpointAuth = OidcProperties.ClientAuth.POST,
        idTokenAlgorithms = listOf("HS256", "ES256"),
        userinfo = OidcProperties.UserinfoMode.NEVER,
        emailTrust = OidcProperties.EmailTrust.NEVER,
    )

    private val PRESETS = mapOf("line" to LINE)

    fun resolve(code: String, p: OidcProperties.Provider): OidcResolved {
        val normalized = code.trim().lowercase()
        val presetName = (p.preset ?: normalized).trim().lowercase()
        require(p.preset == null || presetName in PRESETS) { "skeleton.auth-social-oidc.providers.$code.preset: unknown preset '${p.preset}' (known: ${PRESETS.keys.joinToString()})" }
        val base = PRESETS[presetName] ?: GENERIC
        return OidcResolved(
            code = normalized,
            issuer = p.issuer ?: base.issuer,
            authorizationEndpoint = p.authorizationEndpoint ?: base.authorizationEndpoint,
            tokenEndpoint = p.tokenEndpoint ?: base.tokenEndpoint,
            userinfoEndpoint = p.userinfoEndpoint ?: base.userinfoEndpoint,
            jwksUri = p.jwksUri ?: base.jwksUri,
            clientId = p.clientId.trim(),
            clientSecret = p.clientSecret,
            redirectUri = p.redirectUri?.takeIf { it.isNotBlank() },
            scopes = p.scopes?.takeIf { it.isNotEmpty() } ?: base.scopes!!,
            pkce = p.pkce ?: base.pkce!!,
            nonce = p.nonce ?: base.nonce!!,
            clientAuth = p.tokenEndpointAuth ?: base.tokenEndpointAuth!!,
            algorithms = (p.idTokenAlgorithms?.takeIf { it.isNotEmpty() } ?: base.idTokenAlgorithms!!).map { JWSAlgorithm.parse(it.trim().uppercase()) },
            userinfo = p.userinfo ?: base.userinfo!!,
            emailTrust = p.emailTrust ?: base.emailTrust!!,
            claims = OidcResolved.Claims(
                subject = p.claims.subject ?: "sub", email = p.claims.email ?: "email", emailVerified = p.claims.emailVerified ?: "email_verified",
                name = p.claims.name ?: "name", picture = p.claims.picture ?: "picture",
            ),
            authorizeParams = p.authorizeParams ?: base.authorizeParams!!,
        )
    }
}
