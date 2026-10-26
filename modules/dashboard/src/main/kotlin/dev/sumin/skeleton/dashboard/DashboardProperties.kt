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
) {
    data class Admin(
        /** 관리 API(`/api/v1/admin/dashboard`). 꺼져 있으면 404 */
        val enabled: Boolean = false,
    )

    data class Cache(
        /** 우리 DB 숫자의 캐시 수명 — 같은 범위 질의는 이 시간 안에 한 번만 계산한다 (0 = 끔) */
        val db: Duration = Duration.ofSeconds(30),
    )
}
