package dev.sumin.skeleton.auth.config

import kotlin.test.Test
import kotlin.test.assertFailsWith

class AuthStartupValidatorTest {
    @Test
    fun `validate rejects dev login in prod`() {
        val properties = AuthProperties(
            devLogin = AuthProperties.DevLogin(enabled = true),
        )

        assertFailsWith<IllegalStateException> {
            AuthStartupValidator.validate(properties, activeProfiles = setOf("prod"))
        }
    }

    @Test
    fun `validate rejects dev login in staging`() {
        val properties = AuthProperties(
            devLogin = AuthProperties.DevLogin(enabled = true),
        )

        assertFailsWith<IllegalStateException> {
            AuthStartupValidator.validate(properties, activeProfiles = setOf("staging"))
        }
    }

    @Test
    fun `validate rejects enabled break glass with blank secret`() {
        val properties = AuthProperties(
            breakGlass = AuthProperties.BreakGlass(enabled = true, secret = " "),
        )

        assertFailsWith<IllegalStateException> {
            AuthStartupValidator.validate(properties, activeProfiles = emptySet())
        }
    }

    @Test
    fun `validate rejects enabled break glass in prod with empty allowlist`() {
        val properties = AuthProperties(
            breakGlass = AuthProperties.BreakGlass(enabled = true, secret = "break-glass-secret"),
        )

        assertFailsWith<IllegalStateException> {
            AuthStartupValidator.validate(properties, activeProfiles = setOf("prod"))
        }
    }

    @Test
    fun `validate rejects enabled break glass in staging with empty allowlist`() {
        val properties = AuthProperties(
            breakGlass = AuthProperties.BreakGlass(enabled = true, secret = "break-glass-secret"),
        )

        assertFailsWith<IllegalStateException> {
            AuthStartupValidator.validate(properties, activeProfiles = setOf("staging"))
        }
    }

    @Test
    fun `validate allows enabled break glass in prod with secret and allowlist`() {
        val properties = AuthProperties(
            breakGlass = AuthProperties.BreakGlass(
                enabled = true,
                secret = "break-glass-secret",
                allowedAccountIds = listOf("acc_admin"),
            ),
        )

        AuthStartupValidator.validate(properties, activeProfiles = setOf("prod"))
    }
}
