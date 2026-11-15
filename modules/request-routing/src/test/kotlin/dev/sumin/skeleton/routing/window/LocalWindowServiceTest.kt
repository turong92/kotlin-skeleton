package dev.sumin.skeleton.routing.window

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.authz.OperatorAction
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.routing.StoreLocalRouteWindow
import dev.sumin.skeleton.routing.isOpen
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 창 서비스 — 저장과 감사가 한 트랜잭션, 열고 닫으면 이 인스턴스의 판정 캐시가 바로 바뀌고, 다른 인스턴스는 캐시 시간 안에 따른다 */
class LocalWindowServiceTest {
    private var now = Instant.parse("2026-11-16T00:00:00Z")
    private val time = TimeProvider { now }
    private val store = InMemoryLocalRouteWindowStore()
    private val audits = mutableListOf<OperatorAction>()
    private var auditFails = false
    private val audit = OperatorAuditLog {
        if (auditFails) error("audit down")
        audits += it
    }
    private val ttl = Duration.ofSeconds(5)
    private val window = StoreLocalRouteWindow(store, ttl, time)
    private val service = LocalWindowService(store, window, audit, RollbackingTransactions(store), time, Duration.ofHours(8))

    @Test
    fun `window is invalidated on open and close`() {
        assertFalse(window.isOpen(now)) // 닫힌 판정이 캐시된다

        service.open("alice", 30, "hotfix")
        assertTrue(window.isOpen(now))

        service.close("alice")
        assertFalse(window.isOpen(now))
    }

    @Test
    fun `other instance follows after the cache ttl`() {
        val other = StoreLocalRouteWindow(store, ttl, time)
        assertFalse(other.isOpen(now))

        service.open("alice", 30, "hotfix")
        assertFalse(other.isOpen(now), "ttl 안에서는 옛 값")

        now = now.plus(ttl)
        assertTrue(other.isOpen(now))

        service.close("alice")
        assertTrue(other.isOpen(now), "닫힘도 ttl 안에서는 옛 값")
        now = now.plus(ttl)
        assertFalse(other.isOpen(now))
    }

    @Test
    fun `audit rolls back with the store`() {
        auditFails = true

        assertFailsWith<IllegalStateException> { service.open("alice", 30, "hotfix") }

        assertTrue(store.rows.isEmpty())
        assertNull(service.status())
    }

    @Test
    fun `minutes over the configured max is a validation failure`() {
        val short = LocalWindowService(store, window, audit, RollbackingTransactions(store), time, Duration.ofHours(1))

        val failure = assertFailsWith<ApplicationException> { short.open("alice", 61, "hotfix") }

        assertEquals(PlatformErrorCode.VALIDATION_FAILED, failure.errorCode)
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `close audits each closed window`() {
        service.open("alice", 30, "hotfix")
        audits.clear()

        service.close("bob")

        assertEquals(listOf(OperatorAction("bob", "routing.local-window.closed", "local-window", "1")), audits)
        audits.clear()
        service.close("bob")
        assertTrue(audits.isEmpty())
    }
}
