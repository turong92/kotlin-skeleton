package dev.sumin.skeleton.account.abuse

import dev.sumin.skeleton.account.events.AccountEvent
import dev.sumin.skeleton.account.events.AccountEventType
import dev.sumin.skeleton.alert.AlertKind
import dev.sumin.skeleton.alert.OwnerAlerts
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlertingAccountEventListenerTest {
    private class CapturingAlerts : OwnerAlerts {
        val emitted = mutableListOf<Triple<AlertKind, String, String>>()
        override fun emit(kind: AlertKind, key: String, detail: String, immediate: Boolean) { emitted += Triple(kind, key, detail) }
    }

    @Test
    fun `throttling and refresh-token reuse raise owner alerts that carry no account data`() {
        val alerts = CapturingAlerts()
        val listener = AlertingAccountEventListener(alerts)
        val now = Instant.parse("2026-10-06T00:00:00Z")
        listener.on(AccountEvent(AccountEventType.LOGIN_THROTTLED, null, now, "203.0.113.9", mapOf("scope" to "ip")))
        listener.on(AccountEvent(AccountEventType.REFRESH_REUSE_DETECTED, "acc_1", now))
        listener.on(AccountEvent(AccountEventType.LOGIN_SUCCESS, "acc_1", now))

        assertEquals(listOf("account.login-throttled", "account.refresh-reuse"), alerts.emitted.map { it.first.name })
        assertTrue(alerts.emitted.none { "@" in it.third })
    }
}
