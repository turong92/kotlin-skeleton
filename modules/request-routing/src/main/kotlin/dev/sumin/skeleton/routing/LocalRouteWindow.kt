package dev.sumin.skeleton.routing

import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Duration
import java.time.Instant

data class LocalWindow(
    val id: Long,
    val openedBy: String,
    val reason: String,
    val openedAt: Instant,
    val openUntil: Instant,
    val closedBy: String?,
    val closedAt: Instant?,
)

/** 창 저장 포트 — 구현은 `request-routing-jdbc`. 줄은 지우지 않는다(이력이 곧 기록) */
interface LocalRouteWindowStore {
    /** `closed_at` 이 비고 `open_until` > [now] 인 줄 중 가장 늦게 연 것 */
    fun findOpen(now: Instant): LocalWindow?

    /** 열린 줄을 `closed_by = openedBy` 로 닫고 새 줄을 넣는다 — 호출자의 트랜잭션 안에서 */
    fun open(openedBy: String, reason: String, now: Instant, openUntil: Instant): LocalWindow

    /** 열린 창을 모두 닫고 닫은 창 id 를 돌려준다 */
    fun closeAll(closedBy: String, now: Instant): List<Long>
}

/** 로컬 대상으로 보내도 되는가의 판정 — 들어올 때 · 나갈 때 · 프록시가 모두 이 하나를 본다 */
interface LocalRouteWindow {
    fun openUntil(): Instant?

    /** 연 · 닫은 인스턴스가 자기 캐시를 바로 비운다 */
    fun invalidate() {}
}

fun LocalRouteWindow.isOpen(now: Instant): Boolean = openUntil()?.let { now.isBefore(it) } ?: false

/** 저장소를 [ttl] 동안 캐시해서 읽는다 — 다른 인스턴스가 연 · 닫은 것은 늦어도 [ttl] 뒤에 따른다. 시각이 지나면 캐시 안에서도 닫힌 것으로 본다 */
class StoreLocalRouteWindow(
    private val store: LocalRouteWindowStore,
    private val ttl: Duration,
    private val time: TimeProvider,
) : LocalRouteWindow {
    private class Snapshot(val readAt: Instant, val openUntil: Instant?)

    @Volatile
    private var snapshot: Snapshot? = null

    override fun openUntil(): Instant? {
        val now = time.now()
        fresh(snapshot, now)?.let { return it.openUntil }
        return synchronized(this) {
            (fresh(snapshot, now) ?: Snapshot(now, store.findOpen(now)?.openUntil).also { snapshot = it }).openUntil
        }
    }

    private fun fresh(snapshot: Snapshot?, now: Instant): Snapshot? =
        snapshot?.takeIf { now.isBefore(it.readAt.plus(ttl)) }

    override fun invalidate() {
        snapshot = null
    }
}

/** 저장소 빈이 없을 때 — 창은 늘 닫혀 있다 */
object ClosedLocalRouteWindow : LocalRouteWindow {
    override fun openUntil(): Instant? = null
}
