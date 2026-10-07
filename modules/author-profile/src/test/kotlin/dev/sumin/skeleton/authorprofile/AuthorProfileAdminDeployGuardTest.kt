package dev.sumin.skeleton.authorprofile

import dev.sumin.skeleton.account.events.AccountEvent
import dev.sumin.skeleton.account.events.AccountEventListener
import dev.sumin.skeleton.account.events.LoggingAccountEventListener
import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployEnv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `admin.enabled=true` 인데 로그 한 줄 말고 남는 감사 길이 없으면 알린다 (스펙 §7.9 — 조회 감사가 로그에만 있으면 증거가 못 된다) */
class AuthorProfileAdminDeployGuardTest {
    private val prod = DeployContext(DeployEnv.PROD, emptySet())
    private val local = DeployContext(DeployEnv.LOCAL, emptySet())
    private val unset = DeployContext(null, emptySet())
    private val durable = AccountEventListener { _: AccountEvent -> }

    private fun guard(vararg listeners: AccountEventListener) = AuthorProfileAdminDeployGuard { listeners.toList() }

    @Test
    fun `only the logging listener in a protected env is a warning that names the audit property - never a problem`() {
        val g = guard(LoggingAccountEventListener())
        val warnings = g.warnings(prod)
        assertEquals(1, warnings.size)
        assertTrue("skeleton.account.audit.enabled" in warnings.single() && "AUTHOR_LINK_VIEWED" in warnings.single(), warnings.single())
        assertEquals(emptyList(), g.problems(prod))
    }

    @Test
    fun `any other listener counts as a durable audit path - no warning`() {
        assertEquals(emptyList(), guard(LoggingAccountEventListener(), durable).warnings(prod))
    }

    @Test
    fun `local and unset environments are not warned`() {
        assertEquals(emptyList(), guard(LoggingAccountEventListener()).warnings(local))
        assertEquals(emptyList(), guard(LoggingAccountEventListener()).warnings(unset))
    }
}
