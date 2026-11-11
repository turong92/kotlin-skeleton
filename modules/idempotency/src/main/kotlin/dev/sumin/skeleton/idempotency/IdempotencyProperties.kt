package dev.sumin.skeleton.idempotency

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "skeleton.idempotency")
data class IdempotencyProperties(
    val enabled: Boolean = true,
    val ttl: Duration = Duration.ofHours(24),
    /**
     * 응답을 저장하기 전의 「진행 중」 예약이 유효한 시간 — 저장소가 DB(`idempotency-jdbc`)일 때 쓴다. 요청 도중 프로세스가 죽으면 그 키가 TTL 내내 409 로 묶이지 않도록,
     * 이 시간이 지난 진행 중 예약은 같은 키의 다음 요청이 새로 시작한다. 가장 오래 걸리는 명령보다 길게 둔다 (짧으면 아직 도는 명령이 한 번 더 실행된다)
     */
    val inProgressLease: Duration = Duration.ofMinutes(5),
    val cachedMethods: Set<String> = setOf("POST", "PUT", "PATCH", "DELETE"),
)
