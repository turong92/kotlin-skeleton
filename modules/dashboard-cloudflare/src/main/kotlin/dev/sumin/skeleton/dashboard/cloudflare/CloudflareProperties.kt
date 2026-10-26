package dev.sumin.skeleton.dashboard.cloudflare

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `skeleton.dashboard-cloudflare` — Cloudflare Web Analytics(RUM) 읽기. 계정 id · 사이트 태그 · 토큰이 모두 있어야 연결된 것이다 (아니면 카드가 `NOT_CONNECTED`).
 * 프로젝트 모드에서는 프로젝트마다 자기 `config` 에 셋을 적는다 — 공통 값을 물려받지 않는다. 토큰은 [toString] 에 찍지 않는다.
 */
@ConfigurationProperties("skeleton.dashboard-cloudflare")
data class CloudflareProperties(
    /** Cloudflare 계정 id (대시보드 URL 의 `dash.cloudflare.com/<계정 id>`) */
    val accountId: String = "",
    /** Web Analytics 사이트의 태그 */
    val siteTag: String = "",
    /** API 토큰 — 권한 Account / Account Analytics / Read 하나. 환경변수 `SKELETON_DASHBOARD_CLOUDFLARE_API_TOKEN` 로 넣는다 */
    val apiToken: String = "",
    /** 오늘 숫자(직접 질의)의 서버 캐시 수명 — 운영자가 여럿 열어도 외부 호출은 이 주기당 한 번 */
    val liveCache: Duration = Duration.ofMinutes(5),
    /** 비우지 않으면 이 주소로 보낸다. 시험의 가짜 Cloudflare 는 여기에 적는다 */
    val baseUrl: String = "https://api.cloudflare.com",
    val clientName: String = "dashboard-cloudflare",
    val timeout: Duration = Duration.ofSeconds(10),
) {
    val connected: Boolean get() = accountId.isNotBlank() && siteTag.isNotBlank() && apiToken.isNotBlank()

    override fun toString(): String =
        "CloudflareProperties(accountId=${accountId.ifBlank { "<unset>" }}, siteTag=${siteTag.ifBlank { "<unset>" }}, apiToken=${if (apiToken.isBlank()) "<unset>" else "<redacted>"})"
}
