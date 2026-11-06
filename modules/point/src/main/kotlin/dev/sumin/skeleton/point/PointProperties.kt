package dev.sumin.skeleton.point

import org.springframework.boot.context.properties.ConfigurationProperties

/** `skeleton.point` — 두 길의 스위치만 있다 */
@ConfigurationProperties(prefix = "skeleton.point")
data class PointProperties(
    val admin: Admin = Admin(),
    val http: Http = Http(),
    val operations: Operations = Operations(),
) {
    /** 작업 기록(`PointOperations`)의 불명 건 조회 - 설정 이름은 payment 의 `reconcile` 과 같다. `job-queue-jdbc` 가 있을 때만 일한다 */
    data class Operations(val reconcile: Reconcile = Reconcile()) {
        data class Reconcile(
            /** 영수증을 받은 뒤 첫 조회까지. 진행 중이던 원장 쓰기가 이보다 늦게 끝나면 조회가 한 번 더 돈다 */
            val firstDelay: java.time.Duration = java.time.Duration.ofSeconds(60),
            /** 조회 작업의 시도 수 - 다 쓰면 작업을 포기(ABANDONED)로 닫는다(그 뒤 원장 줄이 나타나도 `resolve` 를 부르면 성공으로 바로잡는다). 큐의 기본 백오프를 따른다 */
            val maxAttempts: Int = 12,
        ) {
            init {
                require(!firstDelay.isNegative && !firstDelay.isZero) { "skeleton.point.operations.reconcile.first-delay must be positive" }
                require(maxAttempts >= 1) { "skeleton.point.operations.reconcile.max-attempts must be at least 1" }
            }
        }
    }

    data class Admin(
        /** true 면 `/api/v1/admin/points` 를 연다 (꺼짐 → 404). 쓰려면 권한 `point.manage` 가 있어야 한다 */
        val enabled: Boolean = false,
    )

    data class Http(
        /** false 면 사용자 경로 `/api/v1/points` 를 닫는다 */
        val enabled: Boolean = true,
    )
}
