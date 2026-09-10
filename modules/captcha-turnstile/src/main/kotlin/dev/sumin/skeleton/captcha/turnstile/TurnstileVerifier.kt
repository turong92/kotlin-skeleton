package dev.sumin.skeleton.captcha.turnstile

import com.fasterxml.jackson.annotation.JsonProperty
import dev.sumin.skeleton.common.http.ExternalHttpClient
import java.time.Duration
import org.slf4j.LoggerFactory

data class TurnstileVerification(
    val success: Boolean,
    val errorCodes: List<String> = emptyList(),
    val hostname: String? = null,
    val action: String? = null,
    val challengeTs: String? = null,
) {
    companion object {
        fun failed(vararg codes: String) = TurnstileVerification(success = false, errorCodes = codes.toList())
    }
}

/** Cloudflare siteverify 응답 */
data class TurnstileSiteverifyResponse(
    val success: Boolean = false,
    @param:JsonProperty("error-codes") @get:JsonProperty("error-codes") val errorCodes: List<String> = emptyList(),
    val hostname: String? = null,
    val action: String? = null,
    @param:JsonProperty("challenge_ts") @get:JsonProperty("challenge_ts") val challengeTs: String? = null,
)

/**
 * 프론트 위젯이 준 토큰(`cf-turnstile-response`)을 Cloudflare 에 검증한다.
 * 네트워크 오류·타임아웃은 예외 대신 `success=false, errorCodes=[internal-error]` 로 돌려준다 (호출자가 폼 에러로 처리).
 */
class TurnstileVerifier(
    private val http: ExternalHttpClient,
    private val properties: TurnstileProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun verify(token: String?, remoteIp: String? = null): TurnstileVerification {
        if (token.isNullOrBlank()) return TurnstileVerification.failed("missing-input-response")
        val form = buildMap {
            put("secret", properties.secretKey)
            put("response", token)
            remoteIp?.takeIf { it.isNotBlank() }?.let { put("remoteip", it) }
        }
        val response = try {
            http.postForm(CLIENT_NAME, properties.siteverifyUrl, form, TurnstileSiteverifyResponse::class.java)
                .block(properties.timeout.coerceAtLeast(Duration.ofMillis(100)))
                ?: return TurnstileVerification.failed("internal-error")
        } catch (ex: Exception) {
            log.warn("Turnstile siteverify call failed: {}", ex.message)
            return TurnstileVerification.failed("internal-error")
        }
        val checks = mutableListOf<String>()
        if (properties.expectedHostname.isNotBlank() && response.hostname != properties.expectedHostname) checks += "hostname-mismatch"
        if (properties.expectedAction.isNotBlank() && response.action != properties.expectedAction) checks += "action-mismatch"
        return TurnstileVerification(
            success = response.success && checks.isEmpty(),
            errorCodes = response.errorCodes + checks,
            hostname = response.hostname,
            action = response.action,
            challengeTs = response.challengeTs,
        )
    }

    companion object {
        /** platform outbound HTTP 클라이언트 이름 (`skeleton.http.clients.turnstile.*` 로 타임아웃 등 조정 가능) */
        const val CLIENT_NAME = "turnstile"
    }
}
