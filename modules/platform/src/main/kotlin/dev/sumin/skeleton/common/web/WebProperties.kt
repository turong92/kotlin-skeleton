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
            "traceparent",
            "X-Request-Id",
            "X-Trace-Id",
        ),
        val exposedHeaders: List<String> = listOf("Location", "traceparent", "X-Trace-Id", "X-Span-Id"),
        val allowCredentials: Boolean = false,
        val maxAge: Duration = Duration.ofHours(1),
    )

    data class RateLimit(
        val enabled: Boolean = false,
        val pathPattern: String = "/api/**",
        val capacity: Int = 60,
        val window: Duration = Duration.ofMinutes(1),
    )
}
