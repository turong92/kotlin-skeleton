package dev.sumin.skeleton.account.events

import dev.sumin.skeleton.account.AccountHarness
import dev.sumin.skeleton.account.abuse.AlertingAccountEventListener
import dev.sumin.skeleton.alert.AlertKind
import dev.sumin.skeleton.alert.OwnerAlerts
import dev.sumin.skeleton.auth.session.SessionEvent
import dev.sumin.skeleton.auth.session.SessionEventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** I5 — 세션 모듈의 사건이 계정 이벤트(감사 표 · 경보)로 이어진다 */
class SessionEventBridgeTest {
    private val h = AccountHarness()
    private val bridge = AccountSessionEventListener(h.publisher)

    @Test
    fun `a detected refresh reuse becomes an account event with the session and the caller's IP`() {
        bridge.on(SessionEvent(SessionEventType.REFRESH_REUSE_DETECTED, "acc_1", "ses_1", ip = "203.0.113.9"))
        val e = h.events.all.single()
        assertEquals(AccountEventType.REFRESH_REUSE_DETECTED, e.type)
        assertEquals("acc_1", e.accountId)
        assertEquals("203.0.113.9", e.ip)
        assertEquals("ses_1", e.detail["session"])
    }

    @Test
    fun `revocations are reported with a count and a reason, never a token`() {
        bridge.on(SessionEvent(SessionEventType.SESSION_REVOKED, "acc_1", "ses_1", count = 1))
        bridge.on(SessionEvent(SessionEventType.SESSIONS_REVOKED, "acc_1", count = 3, reason = "REVOKED_ALL"))
        assertEquals(listOf(AccountEventType.SESSION_REVOKED, AccountEventType.SESSIONS_REVOKED), h.events.types())
        assertEquals("3", h.events.all.last().detail["count"])
        assertEquals("REVOKED_ALL", h.events.all.last().detail["reason"])
    }

    private class Capturing : OwnerAlerts {
        val kinds = mutableListOf<String>()
        override fun emit(kind: AlertKind, key: String, detail: String, immediate: Boolean) { kinds += kind.name }
    }

    @Test
    fun `the whole chain - session event to account event to owner alert`() {
        val alerts = Capturing()
        val chain = DefaultAccountEventPublisher({ h.time.now() }) { listOf(AlertingAccountEventListener(alerts)) }
        AccountSessionEventListener(chain).on(SessionEvent(SessionEventType.REFRESH_REUSE_DETECTED, "acc_1", "ses_1"))
        assertTrue("account.refresh-reuse" in alerts.kinds, alerts.kinds.toString())
    }
}
