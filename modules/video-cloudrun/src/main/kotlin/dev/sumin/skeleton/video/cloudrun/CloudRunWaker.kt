package dev.sumin.skeleton.video.cloudrun

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.video.VideoWakeException
import dev.sumin.skeleton.video.VideoWaker
import dev.sumin.skeleton.video.WakeTicket
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import tools.jackson.databind.json.JsonMapper

/**
 * Cloud Run 작업 실행 호출 - Admin API v2 `POST …/jobs/{job}:run`, 덮어쓰기(overrides) 없이 작업 정의 그대로.
 * 인증은 **서비스 계정 키(JSON)** 로 JWT 를 서명해 토큰 주소에서 접근 토큰을 받는 OAuth2 방식이고(JDK 만, 구글 라이브러리 없음) 토큰은 만료 직전까지 쓴다.
 * 키 · 토큰 · 응답 본문은 로그 · 예외 메시지에 안 남는다. 상한 · 간격 · 경보는 [GuardedWaker] 가 두른다
 */
class CloudRunWaker(
    private val config: CloudRunProperties,
    private val time: TimeProvider,
) : VideoWaker {
    override val enabled: Boolean = true

    private val json = JsonMapper.builder().build()
    private val http = HttpClient.newBuilder().connectTimeout(config.timeout).followRedirects(HttpClient.Redirect.NEVER).build()
    private val key: ServiceKey
    private val runUrl: URI

    @Volatile private var token: AccessToken? = null

    init {
        require(NAME.matches(config.project) && NAME.matches(config.region) && NAME.matches(config.job)) {
            "skeleton.video.cloud-run project, region and job must be set and use only letters, digits, '-' and '_'"
        }
        require(config.tokenUri.startsWith("https://") || config.tokenUri.startsWith("http://")) { "skeleton.video.cloud-run.token-uri must be an http(s) address" }
        key = parseKey(config.credentialsJson)
        runUrl = URI.create("${config.apiBase.trimEnd('/')}/v2/projects/${config.project}/locations/${config.region}/jobs/${config.job}:run")
    }

    override fun wake(): WakeTicket {
        val request = HttpRequest.newBuilder(runUrl).timeout(config.timeout)
            .header("Authorization", "Bearer ${accessToken()}")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}")).build()
        val response = send(request, "run")
        if (response.statusCode() !in 200..299) throw VideoWakeException("Cloud Run job run was refused: HTTP ${response.statusCode()}")
        val name = runCatching { json.readTree(response.body())["name"]?.asString() }.getOrNull()
        return WakeTicket(name?.take(MAX_EXECUTION))
    }

    private fun accessToken(): String {
        val cached = token
        val now = time.now()
        if (cached != null && now.isBefore(cached.expiresAt.minusSeconds(EARLY_REFRESH_SECONDS))) return cached.value
        val form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", Charsets.UTF_8) +
            "&assertion=" + URLEncoder.encode(assertion(now), Charsets.UTF_8)
        val request = HttpRequest.newBuilder(URI.create(config.tokenUri)).timeout(config.timeout)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form)).build()
        val response = send(request, "token")
        if (response.statusCode() !in 200..299) throw VideoWakeException("Google access token request was refused: HTTP ${response.statusCode()}")
        val tree = runCatching { json.readTree(response.body()) }.getOrNull()
        val value = tree?.get("access_token")?.asString()?.takeIf { it.isNotBlank() } ?: throw VideoWakeException("Google access token response could not be read")
        val seconds = tree["expires_in"]?.asLong()?.takeIf { it > 0 } ?: DEFAULT_TOKEN_SECONDS
        return value.also { token = AccessToken(it, now.plusSeconds(seconds)) }
    }

    private fun assertion(now: Instant): String {
        val header = b64(json.writeValueAsBytes(mapOf("alg" to "RS256", "typ" to "JWT")))
        val claims = b64(
            json.writeValueAsBytes(
                mapOf("iss" to key.clientEmail, "scope" to SCOPE, "aud" to config.tokenUri, "iat" to now.epochSecond, "exp" to now.epochSecond + JWT_SECONDS),
            ),
        )
        val signature = Signature.getInstance("SHA256withRSA").apply { initSign(key.privateKey); update("$header.$claims".toByteArray()) }.sign()
        return "$header.$claims.${b64(signature)}"
    }

    private fun send(request: HttpRequest, what: String): HttpResponse<String> = try {
        http.send(request, HttpResponse.BodyHandlers.ofString())
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw VideoWakeException("Cloud Run $what call was interrupted", e)
    } catch (e: IOException) {
        throw VideoWakeException("Cloud Run $what call did not get through: ${e.javaClass.simpleName}", e)
    }

    private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private class AccessToken(val value: String, val expiresAt: Instant)

    private class ServiceKey(val clientEmail: String, val privateKey: PrivateKey)

    private companion object {
        const val SCOPE = "https://www.googleapis.com/auth/cloud-platform"
        const val JWT_SECONDS = 3600L
        const val DEFAULT_TOKEN_SECONDS = 3000L
        const val EARLY_REFRESH_SECONDS = 120L
        const val MAX_EXECUTION = 200
        val NAME = Regex("^[A-Za-z0-9_-]{1,63}$")

        /** 키 JSON 을 읽는다 - 틀리면 값 없이 이유만 */
        fun parseKey(credentials: String): ServiceKey {
            require(credentials.isNotBlank()) { "skeleton.video.cloud-run.credentials-json (the service account key JSON) is required when cloud-run is enabled" }
            val tree = try {
                JsonMapper.builder().build().readTree(credentials)
            } catch (e: Exception) {
                throw IllegalArgumentException("skeleton.video.cloud-run.credentials-json is not readable JSON (${e.javaClass.simpleName})")
            }
            fun field(name: String) = tree[name]?.asString()?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("skeleton.video.cloud-run.credentials-json has no $name")
            val der = try {
                Base64.getMimeDecoder().decode(field("private_key").replace(Regex("-----[A-Z ]+-----"), ""))
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("skeleton.video.cloud-run.credentials-json private_key is not readable")
            }
            val privateKey = try {
                KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
            } catch (e: GeneralSecurityException) {
                throw IllegalArgumentException("skeleton.video.cloud-run.credentials-json private_key is not readable")
            }
            return ServiceKey(field("client_email"), privateKey)
        }
    }
}
