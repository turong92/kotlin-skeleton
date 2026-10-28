package dev.sumin.skeleton.coupon

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * ```yaml
 * skeleton:
 *   coupon:
 *     admin:
 *       enabled: false   # true 면 운영자 경로가 열린다 (꺼져 있으면 404)
 *     http:
 *       enabled: true    # false 면 사용자 경로(쿠폰함 · 코드 등록)를 닫는다
 *     dashboard:
 *       enabled: false   # true 면 관리자 대시보드(dashboard 모듈이 있을 때)에 쿠폰 사용 수 · 많이 쓰인 쿠폰 순위를 기여한다
 * ```
 */
@ConfigurationProperties("skeleton.coupon")
data class CouponProperties(
    val admin: Admin = Admin(),
    val http: Http = Http(),
    val dashboard: Dashboard = Dashboard(),
) {
    data class Admin(
        /** 운영자 경로(`coupon.manage`). 기본 꺼짐 → 경로가 없어 404 */
        val enabled: Boolean = false,
        val basePath: String = "/api/v1/admin",
    )

    data class Dashboard(
        /** 대시보드에 카드를 기여한다. 기본 꺼짐 - 켜는 것은 앱의 선택이다 */
        val enabled: Boolean = false,
    )

    data class Http(
        /** 사용자 경로 — `GET /api/v1/coupons` · `POST /api/v1/coupons/redeem` */
        val enabled: Boolean = true,
        val basePath: String = "/api/v1/coupons",
    )
}
