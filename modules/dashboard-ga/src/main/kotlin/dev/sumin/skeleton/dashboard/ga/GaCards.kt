package dev.sumin.skeleton.dashboard.ga

import dev.sumin.skeleton.common.dashboard.DashboardRange
import dev.sumin.skeleton.common.dashboard.DashboardRanking
import dev.sumin.skeleton.common.dashboard.DashboardResult
import dev.sumin.skeleton.common.dashboard.DashboardRow

/**
 * GA 순위 탭 하나. 열 이름은 설정에 따라 바뀐다 — 핵심 이벤트 이름(`key-event`)이 있을 때만 「Conversions」 열이 있다.
 * `ga.returning` 은 순위 모양의 작은 숫자 카드다: 「New」 · 「Returning」 두 줄, 값은 사용자 수.
 */
class GaRanking(
    override val key: String,
    override val group: String,
    override val label: String,
    override val order: Int,
    private val firstColumn: String,
    private val kind: String,
    private val data: GaSource,
    private val conversions: Boolean = true,
) : DashboardRanking {
    override val source = GaKinds.SOURCE
    override val columns: List<String>
        get() = listOf(firstColumn, "Sessions") + if (conversions && data.conversionsShown()) listOf("Conversions") else emptyList()

    override fun top(range: DashboardRange, limit: Int): DashboardResult<List<DashboardRow>> = data.top(range, kind, limit)
}

/** `ga.returning` — 「New」 · 「Returning」 두 줄을 늘 이 순서로 (없는 쪽은 0) */
class GaReturningRanking(override val order: Int, private val data: GaSource) : DashboardRanking {
    override val key = "ga.returning"
    override val group = "traffic"
    override val label = "New vs returning"
    override val columns = listOf("Visitors", "Users")
    override val source = GaKinds.SOURCE

    override fun top(range: DashboardRange, limit: Int): DashboardResult<List<DashboardRow>> {
        val result = data.top(range, GaKinds.RETURNING, 2)
        if (result.status != DashboardResult.Status.OK) return result
        val users = result.data.orEmpty().associate { it.label to it.values.first() }
        return DashboardResult(DashboardResult.Status.OK, listOf(DashboardRow("New", listOf(users["new"] ?: 0L)), DashboardRow("Returning", listOf(users["returning"] ?: 0L))).take(limit), result.freshness)
    }
}
