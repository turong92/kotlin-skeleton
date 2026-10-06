package dev.sumin.skeleton.auth.config

import dev.sumin.skeleton.auth.account.AccountIdentifier
import dev.sumin.skeleton.auth.account.AuthAccount
import dev.sumin.skeleton.auth.account.AuthAccountRepository
import dev.sumin.skeleton.auth.account.SeedAuthAccountRepository
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class AuthStartupValidatorTest {
    private val strongSecret = "a-production-grade-secret-of-at-least-32-bytes!"
    private val strongJwt = AuthProperties.Jwt(secret = strongSecret)
    private val ownRepository = object : AuthAccountRepository {
        override fun findBy(identifier: AccountIdentifier): AuthAccount? = null
    }
    private val seedRepository = SeedAuthAccountRepository(emptyList())

    @Test
    fun `validate rejects dev login in prod`() {
        val properties = AuthProperties(
            jwt = strongJwt,
            devLogin = AuthProperties.DevLogin(enabled = true),
        )

        val failure = assertFailsWith<IllegalStateException> {
            AuthStartupValidator.validate(properties, activeProfiles = setOf("prod"), accountRepository = ownRepository)
        }
        assertContains(failure.message.orEmpty(), "Dev login")
    }

    @Test
    fun `validate rejects dev login in staging`() {
        val properties = AuthProperties(
            jwt = strongJwt,
            devLogin = AuthProperties.DevLogin(enabled = true),
        )

        val failure = assertFailsWith<IllegalStateException> {
            AuthStartupValidator.validate(properties, activeProfiles = setOf("staging"), accountRepository = ownRepository)
        }
        assertContains(failure.message.orEmpty(), "Dev login")
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
            jwt = strongJwt,
            breakGlass = AuthProperties.BreakGlass(enabled = true, secret = "break-glass-secret"),
        )

        val failure = assertFailsWith<IllegalStateException> {
            AuthStartupValidator.validate(properties, activeProfiles = setOf("prod"), accountRepository = ownRepository)
        }
        assertContains(failure.message.orEmpty(), "allowed account ids")
    }

    @Test
    fun `validate rejects enabled break glass in staging with empty allowlist`() {
        val properties = AuthProperties(
            jwt = strongJwt,
            breakGlass = AuthProperties.BreakGlass(enabled = true, secret = "break-glass-secret"),
        )

        val failure = assertFailsWith<IllegalStateException> {
            AuthStartupValidator.validate(properties, activeProfiles = setOf("staging"), accountRepository = ownRepository)
        }
        assertContains(failure.message.orEmpty(), "allowed account ids")
    }

    @Test
    fun `validate allows enabled break glass in prod with secret and allowlist`() {
        val properties = AuthProperties(
            jwt = strongJwt,
            breakGlass = AuthProperties.BreakGlass(
                enabled = true,
                secret = "break-glass-secret",
                allowedAccountIds = listOf("acc_admin"),
            ),
        )

        AuthStartupValidator.validate(properties, activeProfiles = setOf("prod"), accountRepository = ownRepository)
    }

    // ---- 보호 프로필: JWT 비밀 ----

    @Test
    fun `validate rejects the built-in default jwt secret in prod and names the property`() {
        val failure = assertFailsWith<IllegalStateException> {
            AuthStartupValidator.validate(AuthProperties(), activeProfiles = setOf("prod"), accountRepository = ownRepository)
        }

        assertContains(failure.message.orEmpty(), "skeleton.auth.jwt.secret")
    }

    @Test
    fun `validate rejects a blank jwt secret in staging and names the property`() {
        val properties = AuthProperties(jwt = AuthProperties.Jwt(secret = "  "))

        val failure = assertFailsWith<IllegalStateException> {
            AuthStartupValidator.validate(properties, activeProfiles = setOf("staging"), accountRepository = ownRepository)
        }

        assertContains(failure.message.orEmpty(), "skeleton.auth.jwt.secret")
    }

    @Test
    fun `validate rejects a jwt secret shorter than 32 bytes in prod`() {
        val properties = AuthProperties(jwt = AuthProperties.Jwt(secret = "too-short-secret"))

        val failure = assertFailsWith<IllegalStateException> {
            AuthStartupValidator.validate(properties, activeProfiles = setOf("prod"), accountRepository = ownRepository)
        }

        assertContains(failure.message.orEmpty(), "32")
    }

    @Test
    fun `validate counts jwt secret length in bytes not characters`() {
        // 한글 11자 = 33바이트 → 통과, 10자 = 30바이트 → 실패
        AuthStartupValidator.validate(
            AuthProperties(jwt = AuthProperties.Jwt(secret = "가".repeat(11))),
            activeProfiles = setOf("prod"),
            accountRepository = ownRepository,
        )
        assertFailsWith<IllegalStateException> {
            AuthStartupValidator.validate(
                AuthProperties(jwt = AuthProperties.Jwt(secret = "가".repeat(10))),
                activeProfiles = setOf("prod"),
                accountRepository = ownRepository,
            )
        }
    }

    @Test
    fun `validate accepts a strong jwt secret and a custom repository in prod`() {
        AuthStartupValidator.validate(
            AuthProperties(jwt = strongJwt),
            activeProfiles = setOf("prod"),
            accountRepository = ownRepository,
        )
    }

    // ---- 보호 프로필: 시드 저장소 ----

    @Test
    fun `validate rejects the in-memory seed repository in prod and names the bean`() {
        val failure = assertFailsWith<IllegalStateException> {
            AuthStartupValidator.validate(
                AuthProperties(jwt = strongJwt),
                activeProfiles = setOf("prod"),
                accountRepository = seedRepository,
            )
        }

        assertContains(failure.message.orEmpty(), "AuthAccountRepository")
    }

    @Test
    fun `validate rejects the in-memory seed repository in staging`() {
        assertFailsWith<IllegalStateException> {
            AuthStartupValidator.validate(
                AuthProperties(jwt = strongJwt),
                activeProfiles = setOf("staging"),
                accountRepository = seedRepository,
            )
        }
    }

    // ---- 보호 프로필 밖은 그대로 ----

    @Test
    fun `validate keeps the default secret and the seed repository working outside protected profiles`() {
        listOf(emptySet(), setOf("local"), setOf("dev"), setOf("test")).forEach { profiles ->
            AuthStartupValidator.validate(AuthProperties(), activeProfiles = profiles, accountRepository = seedRepository)
        }
    }

    // ---- 보호 프로필 목록은 설정 가능 ----

    @Test
    fun `validate uses the configured protected profiles instead of the default list`() {
        val properties = AuthProperties(protectedProfiles = listOf("production"))

        assertFailsWith<IllegalStateException> {
            AuthStartupValidator.validate(properties, activeProfiles = setOf("production"), accountRepository = ownRepository)
        }
        // 기본 목록(prod)은 더 이상 보호 프로필이 아니다
        AuthStartupValidator.validate(properties, activeProfiles = setOf("prod"), accountRepository = seedRepository)
    }

    @Test
    fun `validate treats any active profile in the protected list as protected`() {
        assertFailsWith<IllegalStateException> {
            AuthStartupValidator.validate(
                AuthProperties(),
                activeProfiles = setOf("local", "prod"),
                accountRepository = ownRepository,
            )
        }
    }

    @Test
    fun `a blank JWT secret is a named guard problem in every environment - not an Empty key crash from the signer`() {
        val blank = AuthProperties(jwt = AuthProperties.Jwt(secret = ""))
        val unprotected = dev.sumin.skeleton.common.deploy.DeployContext(null, emptySet())
        val problems = AuthStartupValidator.problems(blank, unprotected)
        assertContains(problems.joinToString(), "JWT_SECRET")
        val failure = assertFailsWith<dev.sumin.skeleton.common.deploy.DeployGuardViolationException> { AuthStartupValidator.validateJwtSecret(blank, unprotected) }
        assertContains(failure.message.orEmpty(), "skeleton.auth.jwt.secret")
        assertContains(failure.message.orEmpty(), "secrets")   // tells the deployer the declaration's secrets: list must name it
    }
}
