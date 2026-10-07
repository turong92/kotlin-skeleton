package dev.sumin.skeleton.authorprofile.web

import dev.sumin.skeleton.common.ApplicationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.authority.AuthorityUtils

class AuthorProfileCallersTest {
    private val callers = AuthorProfileCallers()

    private fun refused(block: () -> Unit) = assertEquals("COMMON.UNAUTHORIZED", assertFailsWith<ApplicationException> { block() }.errorCode.code)

    @Test
    fun `no authentication is COMMON_UNAUTHORIZED`() = refused { callers.accountId(null) }

    @Test
    fun `an anonymous token is COMMON_UNAUTHORIZED`() =
        refused { callers.accountId(AnonymousAuthenticationToken("key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"))) }

    @Test
    fun `an unauthenticated token is COMMON_UNAUTHORIZED`() =
        refused { callers.accountId(TestingAuthenticationToken("acc_1", "n/a").also { it.isAuthenticated = false }) }

    @Test
    fun `an authenticated token yields its name`() {
        assertEquals("acc_1", callers.accountId(TestingAuthenticationToken("acc_1", "n/a", "ROLE_USER")))
    }
}
