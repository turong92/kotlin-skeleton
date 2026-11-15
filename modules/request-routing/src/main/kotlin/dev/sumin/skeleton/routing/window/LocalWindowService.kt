package dev.sumin.skeleton.routing.window

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.authz.OperatorAction
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.routing.LocalRouteWindow
import dev.sumin.skeleton.routing.LocalRouteWindowStore
import dev.sumin.skeleton.routing.LocalWindow
import java.time.Duration
import org.springframework.transaction.support.TransactionOperations

/**
 * 로컬 창 열기 · 닫기. 저장과 감사는 한 트랜잭션(감사가 던지면 창 줄도 없다)이고, 끝난 뒤 이 인스턴스의 판정 캐시를 바로 비운다 —
 * 다른 인스턴스는 `local.cache-ttl` 안에 따른다. 감사에는 사유를 싣지 않는다(창 줄에만 남는다).
 */
class LocalWindowService(
    private val store: LocalRouteWindowStore,
    private val window: LocalRouteWindow,
    private val audit: OperatorAuditLog,
    private val tx: TransactionOperations,
    private val time: TimeProvider,
    private val maxWindow: Duration,
) {
    fun status(): LocalWindow? = store.findOpen(time.now())

    fun open(actorId: String, minutes: Int, reason: String): LocalWindow {
        val length = Duration.ofMinutes(minutes.toLong())
        if (length > maxWindow) {
            throw ApplicationException("minutes must not exceed the configured maximum window.", PlatformErrorCode.VALIDATION_FAILED)
        }
        val now = time.now()
        val opened = tx.execute {
            store.open(actorId, reason, now, now.plus(length)).also {
                audit.record(OperatorAction(actorId, OPENED, TARGET_TYPE, it.id.toString(), mapOf("openUntil" to it.openUntil.toString())))
            }
        }
        window.invalidate()
        return opened
    }

    fun close(actorId: String) {
        val now = time.now()
        tx.executeWithoutResult {
            store.closeAll(actorId, now).forEach { audit.record(OperatorAction(actorId, CLOSED, TARGET_TYPE, it.toString())) }
        }
        window.invalidate()
    }

    private companion object {
        const val OPENED = "routing.local-window.opened"
        const val CLOSED = "routing.local-window.closed"
        const val TARGET_TYPE = "local-window"
    }
}
