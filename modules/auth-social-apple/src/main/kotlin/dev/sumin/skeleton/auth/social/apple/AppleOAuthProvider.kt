package dev.sumin.skeleton.auth.social.apple

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jwt.JWTClaimsSet
import dev.sumin.skeleton.auth.social.idtoken.IdTokenRejected
import dev.sumin.skeleton.auth.social.idtoken.IdTokenVerifier
import dev.sumin.skeleton.auth.social.idtoken.JwksCache
import dev.sumin.skeleton.auth.social.oauth.NativeTokenKind
import dev.sumin.skeleton.auth.social.oauth.NonceMode
import dev.sumin.skeleton.auth.social.oauth.OAuthCodeExchange
import dev.sumin.skeleton.auth.social.oauth.OAuthIdTokenInvalidException
import dev.sumin.skeleton.auth.social.oauth.OAuthNativeToken
import dev.sumin.skeleton.auth.social.oauth.OAuthProvider
import dev.sumin.skeleton.auth.social.oauth.OAuthTokenErrors
import dev.sumin.skeleton.auth.social.oauth.OAuthUserProfile
import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.http.ExternalHttpStatusException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import org.slf4j.LoggerFactory

/**
 * Sign in with Apple — iOS 앱 전용(웹 리다이렉트는 없다: [authorize] 가 null).
 * 근거: https://developer.apple.com/documentation/signinwithapplerestapi (토큰 · 철회 · 키), https://developer.apple.com/documentation/sign_in_with_apple/sign_in_with_apple_rest_api/verifying_a_user
 *
 * - 앱의 identityToken: JWKS `{base}/auth/keys`, RS256, `iss`={base}, `aud`=번들 id, exp, **nonce 필수** — 토큰의 nonce 는 요청 원문의 SHA-256 hex.
 * - 앱의 authorizationCode: `POST {base}/auth/token` (client_secret JWT) → id_token 검증 → 같은 프로필. 탈퇴 철회([revoke])도 같은 교환을 쓴다.
 * - 이름은 토큰에 없다 — 앱이 첫 로그인 때 SDK 응답에서 읽어 요청의 `displayName` 으로 넘긴다.
 */
class AppleOAuthProvider(
    private val httpClient: ExternalHttpClient,
    private val properties: AppleProperties,
    clock: Clock = Clock.systemUTC(),
) : OAuthProvider {
    private val log = LoggerFactory.getLogger(AppleOAuthProvider::class.java)

    override val providerId: String = "apple"
    override val nonce: NonceMode = NonceMode.REQUIRED
    override val autoEnabled: Boolean = true
    override val publicClientId: String get() = properties.clientId
    override val nativeToken: NativeTokenKind = NativeTokenKind.ID_TOKEN

    private val base = properties.baseUrl.trimEnd('/')
    private val clientSecret: AppleClientSecret
    private val verifier: IdTokenVerifier

    init {
        require(properties.clientId.isNotBlank()) { "skeleton.auth-social-apple.client-id must not be blank" }
        require(properties.teamId.isNotBlank()) { "skeleton.auth-social-apple.team-id must not be blank (the Team ID of your Apple developer account)" }
        require(properties.keyId.isNotBlank()) { "skeleton.auth-social-apple.key-id must not be blank (the Key ID of the Sign in with Apple key)" }
        require(properties.privateKey.isNotBlank()) { "skeleton.auth-social-apple.private-key must not be blank (the content of the AuthKey_<key id>.p8 file)" }
        clientSecret = AppleClientSecret(properties.teamId, properties.keyId, properties.clientId, base, properties.privateKey, clock)
        verifier = IdTokenVerifier(
            clientId = properties.clientId,
            clientSecret = "",
            algorithms = listOf(JWSAlgorithm.RS256),
            issuers = { setOf(base) },
            jwks = JwksCache(httpClient, { "$base/auth/keys" }, Duration.ofHours(1), Duration.ofSeconds(30), clock),
            skew = Duration.ofSeconds(60),
            clock = clock,
            nonceMatches = { claim, expected -> MessageDigest.isEqual(claim.lowercase().toByteArray(StandardCharsets.UTF_8), sha256Hex(expected).toByteArray(StandardCharsets.UTF_8)) },
        )
    }

    override fun fetchProfile(authorizationCode: String, redirectUri: String?): OAuthUserProfile =
        fetchProfile(OAuthCodeExchange(authorizationCode, redirectUri))

    override fun fetchProfile(exchange: OAuthCodeExchange): OAuthUserProfile {
        val idToken = exchangeCode(exchange.authorizationCode)["id_token"] as? String
            ?: throw IllegalStateException("Apple token response has no id_token")
        return verifyNativeToken(OAuthNativeToken(idToken, exchange.nonce))
    }

    override fun verifyNativeToken(token: OAuthNativeToken): OAuthUserProfile {
        // 코어가 nonce 를 먼저 요구하지만, 이 입구는 nonce 없이는 토큰을 받지 않는다 (재생 방지)
        val expectedNonce = token.nonce ?: throw OAuthIdTokenInvalidException(providerId, "nonce: a nonce is required")
        val claims = try {
            verifier.verify(token.token, expectedNonce)
        } catch (ex: IdTokenRejected) {
            log.warn("ID token of provider 'apple' rejected: {}", ex.reason)
            throw OAuthIdTokenInvalidException(providerId, ex.reason)
        }
        return profileOf(claims, token.displayName)
    }

    /**
     * 탈퇴 철회 — 앱이 탈퇴 직전에 받은 authorizationCode 를 교환해 refresh token 을 얻고 애플에 철회한다 (App Store 의 "Sign in with Apple 사용자 토큰 철회" 요건).
     * 코드를 애플이 거절하면 [dev.sumin.skeleton.auth.social.oauth.OAuthInvalidAuthorizationCodeException], 그 밖의 실패는 그냥 예외(502).
     */
    fun revoke(authorizationCode: String) {
        val refreshToken = exchangeCode(authorizationCode)["refresh_token"] as? String
            ?: throw IllegalStateException("Apple token response has no refresh_token to revoke")
        httpClient.postForm(
            "auth-social-apple-revoke", "$base/auth/revoke",
            mapOf("client_id" to properties.clientId, "client_secret" to clientSecret.issue(), "token" to refreshToken, "token_type_hint" to "refresh_token"),
            String::class.java,
        ) { loggingTag("auth-social.apple.revoke") }.block()
    }

    @Suppress("UNCHECKED_CAST")
    private fun exchangeCode(authorizationCode: String): Map<String, Any?> =
        try {
            requireNotNull(
                httpClient.postForm(
                    "auth-social-apple-token", "$base/auth/token",
                    mapOf("grant_type" to "authorization_code", "code" to authorizationCode, "client_id" to properties.clientId, "client_secret" to clientSecret.issue()),
                    Map::class.java,
                ) { loggingTag("auth-social.apple.token") }.block(),
            ) as Map<String, Any?>
        } catch (ex: ExternalHttpStatusException) {
            throw OAuthTokenErrors.map(providerId, ex, verifierSent = false)
        }

    private fun profileOf(claims: JWTClaimsSet, displayName: String?): OAuthUserProfile {
        val email = claims.getStringClaim("email")?.trim()?.takeIf { it.isNotEmpty() }
        val verified = email != null && claims.claims["email_verified"].isTrue()
        return OAuthUserProfile(
            provider = providerId,
            providerUserId = claims.subject,
            email = email,
            username = null,
            displayName = displayName,
            emailVerified = verified,
            // 릴레이 주소와 애플 도메인만 애플이 주소의 권위자다. 애플 ID 에 붙은 남의 도메인 주소는 주인이 바뀌어도 "확인됨" 으로 남는다 — 병합 · 새 계정 주소에 쓰지 않는다
            emailAuthoritative = verified && email.substringAfterLast('@').lowercase() in APPLE_ADDRESS_DOMAINS,
        )
    }

    /** `email_verified` 는 불리언이거나 문자열 "true" 다 (애플 문서가 둘 다 보여 준다) */
    private fun Any?.isTrue() = this == true || (this is String && equals("true", ignoreCase = true))

    private companion object {
        val APPLE_ADDRESS_DOMAINS = setOf("privaterelay.appleid.com", "icloud.com", "me.com", "mac.com")

        fun sha256Hex(raw: String): String =
            MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
