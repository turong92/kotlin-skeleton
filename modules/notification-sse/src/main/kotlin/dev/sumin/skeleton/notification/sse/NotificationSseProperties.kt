package dev.sumin.skeleton.notification.sse

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("skeleton.notification.sse")
data class NotificationSseProperties(
    val enabled: Boolean = true,
    val timeout: Duration = Duration.ofMinutes(30),
    val publicEndpoint: Boolean = false,
    /**
     * 열린 연결마다 이 간격으로 SSE 주석(`:` 한 줄)을 보낸다 - 조용한 연결을 프록시 · 로드밸런서가 유휴로 끊지 않게(Cloudflare 유휴 100초, 흔한 프록시 60초).
     * 비우면(기본) 꺼짐. 켠다면 15초가 관행이다. 짝 클라이언트(`@skeleton/realtime`)의 `idleTimeoutMs` 는 이 간격의 세 배쯤으로 둔다.
     */
    val heartbeat: Duration? = null,
) {
    init {
        require(heartbeat == null || (!heartbeat.isNegative && !heartbeat.isZero)) { "skeleton.notification.sse.heartbeat must be positive" }
    }
}
