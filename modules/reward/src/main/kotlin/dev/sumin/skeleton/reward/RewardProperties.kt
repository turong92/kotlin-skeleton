package dev.sumin.skeleton.reward

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * ```yaml
 * skeleton:
 *   reward:
 *     admin:
 *       enabled: false   # true 면 운영자 경로가 열린다 (꺼져 있으면 404)
 *     notify:
 *       enabled: true    # notification 이 있을 때만 뜻이 있다
 * ```
 */
@ConfigurationProperties("skeleton.reward")
data class RewardProperties(
    val admin: Admin = Admin(),
    val notify: Notify = Notify(),
    val worker: Worker = Worker(),
) {
    data class Admin(
        /** 운영자 경로(`reward.operate`). 기본 꺼짐 → 경로가 없어 404 */
        val enabled: Boolean = false,
        val basePath: String = "/api/v1/admin/reward-grants",
    )

    data class Notify(
        /** false 면 받았어요 알림을 보내지 않는다 */
        val enabled: Boolean = true,
        /** 알림 주제 */
        val topic: String = "rewards",
    )

    data class Worker(
        /** 지급 작업의 최대 시도 수. 비면 큐의 기본을 따른다 */
        val maxAttempts: Int? = null,
    )
}
