package dev.sumin.skeleton.auth.sessions

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployEnv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthSessionDeployGuardTest {
    private val prod = DeployContext(DeployEnv.PROD, emptySet())
    private val unset = DeployContext(null, emptySet())

    private val prodProfile = DeployContext(null, setOf("prod"))
    private val PROTECTED = listOf("prod", "staging")

    private object NotInMemory : SessionStore by InMemorySessionStore()

    @Test
    fun `the in-memory store is a problem in a protected env only`() {
        val guard = AuthSessionDeployGuard(AuthSessionProperties(), PROTECTED) { InMemorySessionStore() }
        assertTrue(guard.problems(prod).single().contains("in-memory SessionStore"))
        assertEquals(emptyList(), guard.problems(unset))
        assertEquals(emptyList(), guard.problems(DeployContext(DeployEnv.LOCAL, emptySet())))
    }

    @Test
    fun `a real store passes`() {
        assertEquals(emptyList(), AuthSessionDeployGuard(AuthSessionProperties(), PROTECTED) { NotInMemory }.problems(prod))
    }

    @Test
    fun `an insecure refresh cookie is a problem in a protected env`() {
        val props = AuthSessionProperties(delivery = AuthSessionProperties.Delivery.COOKIE, cookie = AuthSessionProperties.Cookie(secure = false))
        assertTrue(AuthSessionDeployGuard(props, PROTECTED) { NotInMemory }.problems(prod).single().contains("cookie.secure=false"))
    }

    @Test
    fun `messages name properties and never values`() {
        val msg = AuthSessionDeployGuard(AuthSessionProperties(), PROTECTED) { InMemorySessionStore() }.problems(prod).joinToString()
        assertTrue("skeleton_refresh" !in msg)
    }

    @Test
    fun `a protected profile without SKELETON_ENV is protected too - the same environments as the account guard`() {
        val guard = AuthSessionDeployGuard(AuthSessionProperties(delivery = AuthSessionProperties.Delivery.COOKIE, cookie = AuthSessionProperties.Cookie(secure = false)), PROTECTED) { InMemorySessionStore() }
        assertEquals(2, guard.problems(prodProfile).size, "prod profile + no switch let an in-memory store and a plain-http cookie through")
        assertEquals(emptyList(), guard.problems(DeployContext(null, setOf("dev"))))
    }

    @Test
    fun `SameSite=None on the refresh cookie is called out in a protected env - CSRF then rests on the header alone`() {
        val props = AuthSessionProperties(delivery = AuthSessionProperties.Delivery.COOKIE, cookie = AuthSessionProperties.Cookie(sameSite = "None"))
        val guard = AuthSessionDeployGuard(props, PROTECTED) { NotInMemory }
        assertTrue(guard.warnings(prod).single().contains("same-site"), guard.warnings(prod).toString())
        assertEquals(emptyList(), guard.warnings(unset))
        assertEquals(emptyList(), AuthSessionDeployGuard(AuthSessionProperties(), PROTECTED) { NotInMemory }.warnings(prod))
    }

    @Test
    fun `a misspelt SameSite value fails at startup instead of quietly dropping the attribute`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { AuthSessionProperties.Cookie(sameSite = "Strcit") }
    }
}
