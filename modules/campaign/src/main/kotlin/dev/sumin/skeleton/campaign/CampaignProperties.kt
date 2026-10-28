package dev.sumin.skeleton.campaign

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * ```yaml
 * skeleton:
 *   campaign:
 *     zone: Asia/Seoul      # 비면 skeleton.time.default-zone, 그것도 없으면 Asia/Seoul
 *     admin:
 *       enabled: false      # true 면 운영자 경로가 열린다 (꺼져 있으면 404)
 * ```
 */
@ConfigurationProperties("skeleton.campaign")
data class CampaignProperties(
    /** 「오늘」을 가르는 서비스 시간대. 보는 사람의 시간대가 아니다 */
    val zone: String? = null,
    val admin: Admin = Admin(),
    val http: Http = Http(),
) {
    data class Admin(
        val enabled: Boolean = false,
        val basePath: String = "/api/v1/admin/campaigns",
    )

    data class Http(
        /** false 면 사용자 경로(출석 · 초대)를 열지 않는다 */
        val enabled: Boolean = true,
    )
}
