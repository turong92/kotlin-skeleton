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
        val capacity: Int = 60,
        val window: Duration = Duration.ofMinutes(1),
    )
}
