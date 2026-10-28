package dev.sumin.skeleton.point

import org.springframework.boot.context.properties.ConfigurationProperties

/** `skeleton.point` — 두 길의 스위치만 있다 */
@ConfigurationProperties(prefix = "skeleton.point")
data class PointProperties(
    val admin: Admin = Admin(),
    val http: Http = Http(),
) {
    data class Admin(
        /** true 면 `/api/v1/admin/points` 를 연다 (꺼짐 → 404). 쓰려면 권한 `point.manage` 가 있어야 한다 */
        val enabled: Boolean = false,
    )

    data class Http(
        /** false 면 사용자 경로 `/api/v1/points` 를 닫는다 */
        val enabled: Boolean = true,
    )
}
