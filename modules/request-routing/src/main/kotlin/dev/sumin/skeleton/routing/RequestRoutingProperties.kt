package dev.sumin.skeleton.routing

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * ```yaml
 * skeleton:
 *   routing:
 *     enabled: false      # 모듈 스위치 — 꺼지면 빈 · 필터 · 커스터마이저가 하나도 없다
 *     service: ""         # 이 서비스 이름 — 프록시 · 큐 차선 · Kafka 거르기의 기준
 *     instance: ""        # 이 인스턴스가 대상이면 그 이름(candidate · alice). 비면 원래 서버
 *     inbound: edge       # edge | internal
 *     services:           # 표시를 넘길 서비스(= skeleton.http.clients 이름)와 대상 허용 목록
 *       orders:
 *         targets:
 *           candidate: { url: "https://candidate---orders-abc.a.run.app" }
 * ```
 */
@ConfigurationProperties("skeleton.routing")
data class RequestRoutingProperties(
    val enabled: Boolean = false,
    val service: String = "",
    val instance: String = "",
    val inbound: Inbound = Inbound.EDGE,
    val services: Map<String, Service> = emptyMap(),
    val admin: Admin = Admin(),
    val local: Local = Local(),
    val proxy: Proxy = Proxy(),
) {
    enum class Inbound { EDGE, INTERNAL }

    data class Service(val targets: Map<String, Target> = emptyMap())

    /** [url] 은 원점만(http · https, 경로 없음). [local] 은 로컬 서버 — 관리 API 로 연 창 안에서만 닿는다. [headers] 는 대상으로 가는 호출에 더한다(예: 터널 서비스 토큰) */
    data class Target(val url: String, val local: Boolean = false, val headers: Map<String, String> = emptyMap())

    data class Admin(
        /** 로컬 창 관리 API(`/api/v1/admin/routing/local-window`) — 기본 꺼짐(404) */
        val enabled: Boolean = false,
    )

    data class Local(
        /** 창 하나의 최대 길이 — 줄일 수만 있다(8시간 초과는 기동 실패) */
        val maxWindow: Duration = Duration.ofHours(8),
        /** 창 판정 캐시 — 다른 인스턴스가 열고 닫은 것을 이 시간 안에 따른다 */
        val cacheTtl: Duration = Duration.ofSeconds(5),
    )

    data class Proxy(
        /** 대상이 응답 머리를 줄 때까지(콜드스타트 여유) */
        val timeout: Duration = Duration.ofSeconds(30),
    )
}
