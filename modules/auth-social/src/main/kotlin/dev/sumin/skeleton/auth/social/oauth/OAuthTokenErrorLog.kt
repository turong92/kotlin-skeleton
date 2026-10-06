package dev.sumin.skeleton.auth.social.oauth

import org.slf4j.LoggerFactory

/**
 * 제공자가 토큰 교환을 거절했을 때 운영자가 원인을 알 수 있게 한 줄을 남긴다 — 사용자에게는 늘 같은 `INVALID_AUTHORIZATION_CODE` 가 가므로
 * (`redirect_uri_mismatch` · `invalid_client`(client secret 오타) · 만료된 코드가 모두 같은 4xx 다) 서버 로그가 유일한 단서다.
 * 제공자 응답의 표준 필드(`error` · `error_description` · 카카오의 `error_code`)만 싣는다. 요청(코드 · client secret)은 싣지 않는다.
 */
object OAuthTokenErrorLog {
    private val log = LoggerFactory.getLogger("dev.sumin.skeleton.auth.social")
    private val FIELD = Regex("\"(error|error_description|error_code)\"\\s*:\\s*\"([^\"]{0,200})\"")

    fun rejected(providerId: String, status: Int, upstreamBody: String) {
        val fields = FIELD.findAll(upstreamBody).associate { it.groupValues[1] to it.groupValues[2].replace(Regex("[\\r\\n]+"), " ") }
        log.warn(
            "social login token exchange rejected provider={} status={} error={} error_code={} description='{}'",
            providerId, status, fields["error"] ?: "-", fields["error_code"] ?: "-", fields["error_description"] ?: "-",
        )
    }
}
