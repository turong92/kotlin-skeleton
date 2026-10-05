package dev.sumin.skeleton.auth.security

import dev.sumin.skeleton.auth.principal.CurrentPrincipal
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthPrincipalAuthenticationTest {
    @Test
    fun `name is the account id so modules can address the caller without depending on auth`() {
        val authentication = AuthPrincipalAuthentication(CurrentPrincipal(accountId = "acc_user", username = "user"))

        assertEquals("acc_user", authentication.name)
    }
}
