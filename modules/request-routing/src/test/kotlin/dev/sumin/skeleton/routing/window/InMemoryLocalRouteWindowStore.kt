package dev.sumin.skeleton.routing.window

import dev.sumin.skeleton.routing.LocalRouteWindowStore
import dev.sumin.skeleton.routing.LocalWindow
import java.time.Instant
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations

/** 시험 전용 메모리 저장소 — 저장소 계약(열면 앞 창을 닫는다 · 줄은 지우지 않는다)만 흉내 낸다 */
class InMemoryLocalRouteWindowStore : LocalRouteWindowStore {
    var rows: List<LocalWindow> = emptyList()

    override fun findOpen(now: Instant): LocalWindow? =
        rows.filter { it.closedAt == null && it.openUntil.isAfter(now) }.maxByOrNull { it.id }

    override fun open(openedBy: String, reason: String, now: Instant, openUntil: Instant): LocalWindow {
        closeAll(openedBy, now)
        val window = LocalWindow((rows.maxOfOrNull { it.id } ?: 0L) + 1, openedBy, reason, now, openUntil, null, null)
        rows = rows + window
        return window
    }

    override fun closeAll(closedBy: String, now: Instant): List<Long> {
        val open = rows.filter { it.closedAt == null && it.openUntil.isAfter(now) }
        rows = rows.map { if (it in open) it.copy(closedBy = closedBy, closedAt = now) else it }
        return open.map { it.id }
    }
}

/** 던지면 저장소를 실행 전 상태로 되돌리는 트랜잭션 흉내 */
class RollbackingTransactions(private val store: InMemoryLocalRouteWindowStore) : TransactionOperations {
    override fun <T> execute(action: TransactionCallback<T>): T {
        val before = store.rows
        try {
            return action.doInTransaction(org.springframework.transaction.support.SimpleTransactionStatus())
        } catch (e: Throwable) {
            store.rows = before
            throw e
        }
    }
}
