package dev.sumin.skeleton.notification.push

import dev.sumin.skeleton.auth.principal.CurrentPrincipal
import dev.sumin.skeleton.auth.session.SessionEvent
import dev.sumin.skeleton.auth.session.SessionEventListener
import dev.sumin.skeleton.auth.session.SessionEventType
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class PushSessionEventsTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PushSessionAutoConfiguration::class.java))
        .withPropertyValues("skeleton.notification.push.enabled=true")

    private fun withStore(block: (FakePushDeviceStore, SessionEventListener) -> Unit) {
        val store = FakePushDeviceStore()
        runner.withBean(PushDeviceStore::class.java, { store }).run { context ->
            block(store, context.getBean(SessionEventListener::class.java))
        }
    }

    @Test
    fun `a revoked session removes only the devices registered by that session`() = withStore { store, listener ->
        store.add("acc_a", "inst_s1_0123456789", sessionId = "ses_1")
        store.add("acc_a", "inst_s2_0123456789", sessionId = "ses_2")
        store.add("acc_b", "inst_s3_0123456789", sessionId = "ses_1")   // 다른 계정의 같은 세션 id 는 건드리지 않는다

        listener.on(SessionEvent(SessionEventType.SESSION_REVOKED, "acc_a", sessionId = "ses_1"))

        assertEquals(listOf("inst_s2_0123456789", "inst_s3_0123456789"), store.devices.map { it.installationId })
    }

    @Test
    fun `revoking all sessions and a refresh reuse detection remove every device of the account`() = withStore { store, listener ->
        store.add("acc_a", "inst_s1_0123456789", sessionId = "ses_1")
        store.add("acc_a", "inst_s2_0123456789", sessionId = null)
        store.add("acc_b", "inst_s3_0123456789", sessionId = "ses_3")

        listener.on(SessionEvent(SessionEventType.SESSIONS_REVOKED, "acc_a", count = 2))
        assertEquals(listOf("acc_b"), store.devices.map { it.accountId })

        store.add("acc_b", "inst_s4_0123456789")
        listener.on(SessionEvent(SessionEventType.REFRESH_REUSE_DETECTED, "acc_b", sessionId = "ses_3"))
        assertEquals(emptyList(), store.devices.map { it.accountId })
    }

    @Test
    fun `the session id of the caller comes from the token principal`() {
        runner.withBean(PushDeviceStore::class.java, { FakePushDeviceStore() }).run { context ->
            val resolver = context.getBean(PushSessionIdResolver::class.java)
            assertEquals("ses_9", resolver.sessionIdOf(CurrentPrincipal(accountId = "acc_a", sessionId = "ses_9")))
            assertEquals(null, resolver.sessionIdOf("plain-principal"))
        }
    }
}
