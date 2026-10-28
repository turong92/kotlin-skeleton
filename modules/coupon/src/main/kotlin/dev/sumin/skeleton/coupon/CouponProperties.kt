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
 * ```
 */
@ConfigurationProperties("skeleton.coupon")
data class CouponProperties(
    val admin: Admin = Admin(),
    val http: Http = Http(),
) {
    data class Admin(
        /** 운영자 경로(`coupon.manage`). 기본 꺼짐 → 경로가 없어 404 */
        val enabled: Boolean = false,
        val basePath: String = "/api/v1/admin",
    )

    data class Http(
        /** 사용자 경로 — `GET /api/v1/coupons` · `POST /api/v1/coupons/redeem` */
        val enabled: Boolean = true,
        val basePath: String = "/api/v1/coupons",
    )
}
