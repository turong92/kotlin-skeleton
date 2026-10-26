package dev.sumin.skeleton.common.dashboard

import java.time.Instant
import java.time.ZoneId

// 관리자 대시보드가 모으는 카드의 기여 계약 — 타입만 있고 빈은 없다.
// `account-jdbc` · `payment-jdbc` 같은 모듈이 `dashboard` 를 컴파일에 끌어오지 않고 카드를 기여하려고 platform 에 둔다 (authz 의 PermissionDeclaration 과 같은 이유).
// 기여하는 쪽은 자기 AutoConfiguration 을 `@ConditionalOnClass(name = ["…dashboard…"])` 로 등록한다 — 대시보드를 안 붙인 앱에는 아무것도 생기지 않는다.

/** 조회 기간 `[from, to)`. [zone] 은 대시보드 시간대 — 일 · 주 간격을 어느 날 경계로 끊을지 */
data class DashboardRange(val from: Instant, val to: Instant, val zone: ZoneId)

enum class DashboardInterval { HOUR, DAY, WEEK }

enum class DashboardUnit { PEOPLE, COUNT, MONEY, PERCENT }

/** 돈이 아닌 지표는 [currency] 가 null */
data class DashboardAmount(val amount: Long, val currency: String? = null)

data class DashboardPoint(val at: Instant, val value: Long)

data class DashboardRow(val label: String, val values: List<Long>)

/** [asOf] — LIVE: 계산한 시각, CACHED: 외부에서 가져온 시각, DAILY: 적재한 시각 */
data class DashboardFreshness(val kind: Kind, val asOf: Instant?) {
    enum class Kind { LIVE, CACHED, DAILY }
}

/** 기여가 돌려주는 포장. 키가 없거나 외부 호출이 실패해도 예외 대신 [status] 로 말한다 — 화면 전체를 500 으로 만들지 않는다 */
data class DashboardResult<T>(val status: Status, val data: T? = null, val freshness: DashboardFreshness? = null) {
    enum class Status { OK, NOT_CONNECTED, NO_HISTORY, ERROR }
}

/** 숫자 타일 하나 */
interface DashboardMetric {
    val key: String
    val label: String
    val unit: DashboardUnit
    val source: String
    val order: Int
    val link: String? get() = null

    /** false 면 직전 기간을 계산하지 않는다 (예: 활성 이용자 — 지난 기간 값이 덮어쓰여 적게 나온다) */
    val comparable: Boolean get() = true

    fun value(range: DashboardRange): DashboardResult<List<DashboardAmount>>

    fun series(range: DashboardRange, interval: DashboardInterval): DashboardResult<List<DashboardPoint>>
}

/** 순위 카드의 탭 하나. [group] 은 카드 묶음(`traffic` · `pages` · `geo` · `app`) */
interface DashboardRanking {
    val key: String
    val group: String
    val label: String
    val columns: List<String>
    val source: String
    val order: Int

    fun top(range: DashboardRange, limit: Int): DashboardResult<List<DashboardRow>>
}

/** 퍼널의 묶음에 든 한 사람. 메모리에서만 넘기고 저장하지 않는다 */
data class DashboardCohortMember(val accountId: String, val joinedAt: Instant)

/** 퍼널의 첫 단계 — 기간 안에 들어온 사람의 묶음을 정한다 */
interface DashboardCohortSource {
    val key: String
    val label: String

    fun members(range: DashboardRange): List<DashboardCohortMember>
}

/** 묶음 중 이 단계에 닿은 사람 수. 구현은 계정 id 를 1000 개씩 끊어 IN 질의한다 */
interface DashboardFunnelStep {
    val key: String
    val label: String
    val order: Int

    fun reached(cohort: List<DashboardCohortMember>): Long
}

/**
 * 같은 사람 묶음 퍼널. 앱이 이 빈을 두면 `dashboard` 가 모으는 기본 퍼널(묶음 하나 + 단계 빈들)이 물러난다.
 * [referenceMetricKey] 는 묶음 앞에 참고 숫자로 붙일 타일(익명 방문 — 묶음에 못 넣는다), null 이면 붙이지 않는다
 */
class DashboardFunnel(
    val cohort: DashboardCohortSource,
    val steps: List<DashboardFunnelStep>,
    val referenceMetricKey: String? = "cloudflare.visits",
)
