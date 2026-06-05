package dev.sumin.skeleton.auth.principal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CurrentPrincipalTest {
    @Test
    fun `principal exposes account identity and roles`() {
        val principal = CurrentPrincipal(
            accountId = "acc_123",
            username = "sumin",
            email = "sumin@example.com",
            roles = setOf("USER", "ADMIN"),
        )

        assertEquals("acc_123", principal.accountId)
        assertEquals("sumin", principal.username)
        assertEquals("sumin@example.com", principal.email)
        assertTrue(principal.hasRole("USER"))
        assertTrue(principal.hasRole("ADMIN"))
        assertFalse(principal.hasRole("OWNER"))
    }
}
