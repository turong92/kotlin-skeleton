package dev.sumin.skeleton.point

import org.springframework.boot.context.properties.ConfigurationProperties

/** `skeleton.point` — 두 길의 스위치만 있다 */
@ConfigurationProperties(prefix = "skeleton.point")
data class PointProperties(
    val admin: Admin = Admin(),
    val http: Http = Http(),
    val operations: Operations = Operations(),
    val reservations: Reservations = Reservations(),
) {
    /** 잡아 두기(`PointWallet.reserve`) - 기본 꺼짐. 켜면 잡은 채 버려진 건을 풀 주기 작업이 돌 길(`scheduler` 모듈이거나 요청 구동 모드)이 있어야 한다 */
    data class Reservations(
        val enabled: Boolean = false,
        /** 잡은 뒤 이 시간이 지나도 확정 · 풀기가 없으면 저절로 풀린다. 일(생성 작업)이 걸리는 가장 긴 시간보다 길어야 한다 - 풀린 뒤의 확정은 거절된다 */
        val holdTimeout: java.time.Duration = java.time.Duration.ofMinutes(30),
    ) {
        init {
            require(!holdTimeout.isNegative && !holdTimeout.isZero) { "skeleton.point.reservations.hold-timeout must be positive" }
        }
    }

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
