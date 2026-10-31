package dev.sumin.skeleton.notification.fcm

import dev.sumin.skeleton.common.time.TimeProvider
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.Signature
import java.time.Duration
import java.time.Instant
import java.util.Base64
import tools.jackson.databind.json.JsonMapper

/** 접근 토큰을 못 얻었다 - [retry] 면 구글 쪽 일시 장애(나중에 다시), 아니면 우리 자격이 거절된 것. 메시지에는 비밀이 없다 */
class FcmTokenException(val retry: Boolean, message: String) : RuntimeException(message, null, false, false)

/**
 * 서비스 계정 -> OAuth2 접근 토큰(JWT bearer, RFC 7523). 토큰은 만료 5분 전까지 다시 쓴다. 요청 주소는 고정이다 - 서비스 계정 JSON 의 `token_uri` 는 읽지도 않는다
 * (자격 파일이 서명한 JWT 를 임의의 주소로 보내게 만들 수 있다).
 */
internal class FcmAccessTokens(
    private val credentials: FcmCredentials,
    private val http: FcmHttp,
    private val time: TimeProvider,
    private val timeout: Duration,
) {
    private var token: String? = null
    private var expiresAt: Instant = Instant.MIN

    @Synchronized
    fun get(): String {
        val now = time.now()
        token?.takeIf { now.isBefore(expiresAt.minus(REFRESH_MARGIN)) }?.let { return it }
        val fresh = fetch(now)
        token = fresh.first
        expiresAt = now.plusSeconds(fresh.second)
        return fresh.first
    }

    /** 구글이 토큰을 거절했을 때 - 다음 보내기가 새로 받는다 */
    @Synchronized
    fun invalidate() {
        token = null
    }

    private fun fetch(now: Instant): Pair<String, Long> {
        val form = "grant_type=${enc(GRANT_TYPE)}&assertion=${enc(assertion(now.epochSecond))}"
        val response = try {
            http.post(TOKEN_URL, mapOf("Content-Type" to "application/x-www-form-urlencoded"), form.toByteArray(StandardCharsets.UTF_8), timeout)
        } catch (_: IOException) {
            throw FcmTokenException(true, "the Google token endpoint could not be reached")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw FcmTokenException(true, "interrupted while asking Google for an access token")
        }
        if (response.status == 429 || response.status in 500..599) throw FcmTokenException(true, "the Google token endpoint answered HTTP ${response.status}")
        if (response.status !in 200..299) throw FcmTokenException(false, "Google refused the service account (HTTP ${response.status}): check ${FcmConfigCheck.PROPERTY}")
        val answer = runCatching { JSON.readTree(response.body.orEmpty()) }.getOrNull()
        val accessToken = answer?.path("access_token")?.asString()?.takeIf { it.isNotBlank() }
            ?: throw FcmTokenException(false, "the Google token endpoint answered without an access token")
        val lifetime = answer.path("expires_in").asLong(DEFAULT_LIFETIME_SECONDS).coerceIn(1, MAX_LIFETIME_SECONDS)
        return accessToken to lifetime
    }

    private fun assertion(nowEpochSecond: Long): String {
        val header = b64(JSON.writeValueAsBytes(linkedMapOf("alg" to "RS256", "typ" to "JWT")))
        val claims = b64(
            JSON.writeValueAsBytes(
                linkedMapOf(
                    "iss" to credentials.clientEmail, "scope" to SCOPE, "aud" to TOKEN_URL.toString(),
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

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private companion object {
        val JSON: JsonMapper = JsonMapper.builder().build()
        val TOKEN_URL: URI = URI.create("https://oauth2.googleapis.com/token")
        const val SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
        const val GRANT_TYPE = "urn:ietf:params:oauth:grant-type:jwt-bearer"
        const val MAX_LIFETIME_SECONDS = 3600L
        const val DEFAULT_LIFETIME_SECONDS = 3600L
        val REFRESH_MARGIN: Duration = Duration.ofMinutes(5)
    }
}
