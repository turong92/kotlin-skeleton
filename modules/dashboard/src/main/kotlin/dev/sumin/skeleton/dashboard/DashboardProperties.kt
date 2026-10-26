package dev.sumin.skeleton.dashboard

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("skeleton.dashboard")
data class DashboardProperties(
    val admin: Admin = Admin(),
    /** 대시보드 시간대. 비면 `skeleton.time.default-zone`, 그것도 없으면 UTC */
    val zone: String = "",
    /** 돈 타일이 먼저 보여 줄 통화(ISO 코드). 비면 금액이 큰 통화부터 */
    val currency: String = "",
    val cache: Cache = Cache(),
    val ingest: Ingest = Ingest(),
) {
    data class Admin(
        /** 관리 API(`/api/v1/admin/dashboard`). 꺼져 있으면 404 */
        val enabled: Boolean = false,
    )

    data class Ingest(
        /** 외부 출처의 일별 적재 작업. 저장소(`dashboard-jdbc`)와 적재기(출처 모듈)가 있을 때만 일을 한다 */
        val enabled: Boolean = true,
        /** 매일 이 시각(대시보드 시간대)에 돈다. 기동 직후에도 한 번 돈다 */
        val at: java.time.LocalTime = java.time.LocalTime.of(4, 0),
        /** 한 번에 어제부터 거슬러 몇 일을 다시 적재하나 — 늦게 확정되는 숫자와 쉬었던 날을 메운다. 같은 날을 다시 적재해도 같은 행이다 */
        val backfillDays: Int = 3,
    )

    data class Cache(
        /** 우리 DB 숫자의 캐시 수명 — 같은 범위 질의는 이 시간 안에 한 번만 계산한다 (0 = 끔) */
        val db: Duration = Duration.ofSeconds(30),
    )
}
