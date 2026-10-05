package dev.sumin.skeleton.auth.sessions

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployEnv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthSessionDeployGuardTest {
    private val prod = DeployContext(DeployEnv.PROD, emptySet())
    private val unset = DeployContext(null, emptySet())

    private object NotInMemory : SessionStore by InMemorySessionStore()

    @Test
    fun `the in-memory store is a problem in a protected env only`() {
        val guard = AuthSessionDeployGuard(AuthSessionProperties()) { InMemorySessionStore() }
        assertTrue(guard.problems(prod).single().contains("in-memory SessionStore"))
        assertEquals(emptyList(), guard.problems(unset))
        assertEquals(emptyList(), guard.problems(DeployContext(DeployEnv.LOCAL, emptySet())))
    }

    @Test
    fun `a real store passes`() {
        assertEquals(emptyList(), AuthSessionDeployGuard(AuthSessionProperties()) { NotInMemory }.problems(prod))
    }

    @Test
    fun `an insecure refresh cookie is a problem in a protected env`() {
        val props = AuthSessionProperties(delivery = AuthSessionProperties.Delivery.COOKIE, cookie = AuthSessionProperties.Cookie(secure = false))
        assertTrue(AuthSessionDeployGuard(props) { NotInMemory }.problems(prod).single().contains("cookie.secure=false"))
    }

    @Test
    fun `messages name properties and never values`() {
        val msg = AuthSessionDeployGuard(AuthSessionProperties()) { InMemorySessionStore() }.problems(prod).joinToString()
        assertTrue("skeleton_refresh" !in msg)
    }
}
