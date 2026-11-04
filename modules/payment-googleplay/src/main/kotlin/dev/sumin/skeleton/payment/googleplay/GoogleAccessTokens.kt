package dev.sumin.skeleton.payment.googleplay

import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.time.TimeProvider
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.Signature
import java.time.Duration
import java.time.Instant
import java.util.Base64
import tools.jackson.databind.json.JsonMapper

/**
 * 서비스 계정 -> OAuth2 접근 토큰(JWT bearer, RFC 7523, 범위 `androidpublisher`). 토큰은 만료 5분 전까지 다시 쓴다.
 * `notification-fcm` 의 `FcmAccessTokens` 와 같은 모양을 이 모듈 안에 옮긴 것이다(공용으로 빼지 않는다 - 쓰는 곳이 둘뿐이다).
 * 요청 주소는 설정의 [GooglePlayProperties.tokenUrl](구글 것으로 고정, 보호 환경에서는 바뀌면 기동 실패)이고 서비스 계정 JSON 의 `token_uri` 는 읽지도 않는다
 * (자격 파일이 서명한 JWT 를 임의의 주소로 보내게 만들 수 있다). 실패는 [dev.sumin.skeleton.payment.PaymentProviderException] - 호출한 쪽이 다시 한다.
 */
internal class GoogleAccessTokens(
    private val credentials: GooglePlayCredentials,
    private val http: ExternalHttpClient,
    private val properties: GooglePlayProperties,
    private val time: TimeProvider,
) {
    private var token: String? = null
    private var expiresAt: Instant = Instant.MIN
    private val tokenUri: URI = URI.create(properties.resolvedTokenUrl)
    private val errorMapper = GooglePlayErrorMapper(properties.providerId)

    @Synchronized
    fun get(): String {
        val now = time.now()
        token?.takeIf { now.isBefore(expiresAt.minus(REFRESH_MARGIN)) }?.let { return it }
        val answer = request(now)
        val accessToken = answer.path("access_token").asString().takeIf { it.isNotBlank() } ?: throw GooglePlayFailures.unreadable(properties)
        token = accessToken
        expiresAt = now.plusSeconds(answer.path("expires_in").asLong(MAX_LIFETIME_SECONDS).coerceIn(1, MAX_LIFETIME_SECONDS))
        return accessToken
    }

    /** 구글이 토큰을 거절했을 때 - 다음 호출이 새로 받는다. */
    @Synchronized
    fun invalidate() {
        token = null
    }

    private fun request(now: Instant) =
        http.postForm(
            clientName = properties.clientName,
            path = tokenUri.rawPath.ifEmpty { "/" },
            form = mapOf("grant_type" to GRANT_TYPE, "assertion" to assertion(now.epochSecond)),
            responseType = String::class.java,
        ) {
            baseUrl("${tokenUri.scheme}://${tokenUri.rawAuthority}")
            timeout(properties.lookupTimeout)
            loggingTag("payment.googleplay.token")
            errorMapper(errorMapper)
        }.block()?.let { JSON.readTree(it) } ?: throw GooglePlayFailures.unreadable(properties)

    private fun assertion(nowEpochSecond: Long): String {
        val header = b64(JSON.writeValueAsBytes(linkedMapOf("alg" to "RS256", "typ" to "JWT")))
        val claims = b64(
            JSON.writeValueAsBytes(
                linkedMapOf(
                    "iss" to credentials.clientEmail, "scope" to SCOPE, "aud" to tokenUri.toString(),
                    "iat" to nowEpochSecond, "exp" to nowEpochSecond + MAX_LIFETIME_SECONDS,
                ),
            ),
        )
        val signing = "$header.$claims"
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(credentials.privateKey)
            update(signing.toByteArray(StandardCharsets.US_ASCII))
            sign()
        }
        return "$signing.${b64(signature)}"
    }

    private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private companion object {
        val JSON: JsonMapper = JsonMapper.builder().build()
        const val SCOPE = "https://www.googleapis.com/auth/androidpublisher"
        const val GRANT_TYPE = "urn:ietf:params:oauth:grant-type:jwt-bearer"
        const val MAX_LIFETIME_SECONDS = 3600L
        val REFRESH_MARGIN: Duration = Duration.ofMinutes(5)
    }
}
