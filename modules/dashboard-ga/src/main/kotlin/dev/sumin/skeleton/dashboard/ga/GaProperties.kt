package dev.sumin.skeleton.dashboard.ga

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `skeleton.dashboard-ga` — Google Analytics 4 Data API 읽기. 속성 id 와 서비스 계정 키가 모두 있어야 연결된 것이다 (아니면 카드가 `NOT_CONNECTED`).
 * 프로젝트 모드에서는 프로젝트마다 자기 `config` 에 둘을 적는다 — 공통 값을 물려받지 않는다. 키는 [toString] 에 찍지 않는다.
 */
@ConfigurationProperties("skeleton.dashboard-ga")
data class GaProperties(
    /** GA4 속성 id (숫자, `properties/` 접두 없이) */
    val propertyId: String = "",
    /** 서비스 계정 키 JSON 을 base64 한 것 — 환경변수 `SKELETON_DASHBOARD_GA_CREDENTIALS`. 속성에 「뷰어」로 추가된 계정이어야 한다 */
    val credentials: String = "",
    /** 전환으로 셀 GA 핵심 이벤트(key event) 이름 (예: `purchase`). 비면 전환 열을 숨긴다 */
    val keyEvent: String = "",
    /** 현재 방문자(실시간)의 서버 캐시 수명 — 운영자가 여럿 열어도 외부 호출은 이 주기당 프로젝트당 한 번 */
    val realtimeCache: Duration = Duration.ofSeconds(60),
    /** Data API 주소. 시험의 가짜 GA 는 여기에 적는다 */
    val baseUrl: String = "https://analyticsdata.googleapis.com",
    /** OAuth 토큰 주소(서비스 계정 JWT 를 바꿔 준다). 시험의 가짜 GA 는 여기에 적는다 */
    val tokenUrl: String = "https://oauth2.googleapis.com/token",
    val clientName: String = "dashboard-ga",
    val timeout: Duration = Duration.ofSeconds(10),
) {
    val connected: Boolean get() = propertyId.isNotBlank() && credentials.isNotBlank()

    override fun toString(): String =
        "GaProperties(propertyId=${propertyId.ifBlank { "<unset>" }}, credentials=${if (credentials.isBlank()) "<unset>" else "<redacted>"}, keyEvent=${keyEvent.ifBlank { "<unset>" }})"
}
