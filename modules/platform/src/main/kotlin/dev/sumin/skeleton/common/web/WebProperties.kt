package dev.sumin.skeleton.common.web

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("skeleton.web")
data class WebProperties(
    val publicEndpoints: List<PublicEndpointProperties> = emptyList(),
    val forwardedHeaders: ForwardedHeaders = ForwardedHeaders(),
    val securityHeaders: SecurityHeaders = SecurityHeaders(),
    val cors: Cors = Cors(),
    val rateLimit: RateLimit = RateLimit(),
    val clientIp: ClientIp = ClientIp(),
) {
    data class PublicEndpointProperties(
        val method: String? = null,
        val path: String,
    )

    data class ForwardedHeaders(
        val enabled: Boolean = true,
    )

    data class SecurityHeaders(
        val enabled: Boolean = true,
        val contentSecurityPolicy: String = "",
        val hsts: Hsts = Hsts(),
    ) {
        data class Hsts(
            val enabled: Boolean = true,
            val maxAge: Long = 31_536_000,
            val includeSubDomains: Boolean = true,
        )
    }

    data class Cors(
        val enabled: Boolean = false,
        val pathPattern: String = "/api/**",
        val allowedOriginPatterns: List<String> = listOf("http://localhost:[*]", "http://127.0.0.1:[*]"),
        val allowedMethods: List<String> = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"),
        val allowedHeaders: List<String> = listOf(
            "Authorization",
            "Content-Type",
            "Idempotency-Key",
            "traceparent",
            "X-Request-Id",
            "X-Trace-Id",
            "X-Time-Zone", // react-skeleton api-client 가 모든 요청에 붙인다 (modules/time 의 zone-header 기본값)
            "X-Dev-Account-Id",
            "X-Dev-Username",
            "X-Dev-Email",
            "X-Break-Glass-Account-Id",
            "X-Break-Glass-Reason",
            "X-Break-Glass-Secret",
            "X-Board-Guest-Secret", // board 의 비회원 글 · 댓글 비밀값 (skeleton.board.guest.enabled)
            "X-Workspace-Id", // workspace 의 CurrentWorkspace - 지금 워크스페이스
        ),
        val exposedHeaders: List<String> = listOf(
            "Location",
            "Idempotency-Key",
            "Idempotency-Replayed",
            "traceparent",
            "X-Trace-Id",
            "X-Span-Id",
            "Date", // 서버 시각 — CORS 로 떨어진 프론트가 expiresAt · resendAvailableAt 카운트다운을 서버 시계에 맞춘다 (CORS 안전 목록이 아니라 노출해야 읽힌다)
        ),
        val allowCredentials: Boolean = false,
        val maxAge: Duration = Duration.ofHours(1),
    )

    /**
     * 실제 클라이언트 IP 규칙 ([ClientIps]). **mode 를 정하지 않으면 지금까지처럼 `remoteAddr` 그대로** — 켜져 있는 `ForwardedHeaderFilter` 가
     * `X-Forwarded-For` 로 그 값을 덮어쓰므로 직접 붙은 호출자가 자기 IP(= 한도 키)를 고를 수 있다. 공개 서비스는 mode 를 명시한다.
     */
    data class ClientIp(
        val mode: ClientIpMode? = null,
        /** 전달 헤더를 믿을 피어 CIDR (같은 호스트의 cloudflared · 리버스 프록시 = 루프백이 기본) */
        val trustedProxies: List<String> = listOf("127.0.0.0/8", "::1/128"),
    )

    data class RateLimit(
        val enabled: Boolean = false,
        val pathPattern: String = "/api/**",
        /**
         * 한도를 걸 경로 패턴 **목록**(예: 배지 프로필 + 리뷰 공개 읽기). 기본은 비어 있다 - 비어 있으면 위 [pathPattern] 하나만 쓰던 지금까지 그대로다.
         * 하나라도 있으면 이 목록이 [pathPattern] 을 대신한다(둘을 합치지 않는다 - 기본값(/api 아래 전부)이 목록을 삼키지 않게). 목록의 경로는 한 클라이언트(IP)가 **한 카운터를 나눠 쓴다**.
         */
        val pathPatterns: List<String> = emptyList(),
        val capacity: Int = 60,
        val window: Duration = Duration.ofMinutes(1),
    ) {
        /** 실제로 걸 패턴 - 목록이 있으면 목록, 없으면 단일 [pathPattern] */
        fun effectivePatterns(): List<String> = pathPatterns.filter { it.isNotBlank() }.ifEmpty { listOf(pathPattern) }
    }
}
