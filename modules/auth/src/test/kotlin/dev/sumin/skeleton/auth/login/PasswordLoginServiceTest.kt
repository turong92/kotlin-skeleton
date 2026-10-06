package dev.sumin.skeleton.auth.login

import dev.sumin.skeleton.auth.account.AccountIdentifier
import dev.sumin.skeleton.auth.account.AuthAccount
import dev.sumin.skeleton.auth.account.AuthAccountRepository
import dev.sumin.skeleton.auth.account.LoginBlock
import dev.sumin.skeleton.auth.api.AuthErrorCode
import dev.sumin.skeleton.auth.api.AuthTokenResponseFactory
import dev.sumin.skeleton.auth.api.InvalidCredentialsException
import dev.sumin.skeleton.auth.api.PasswordLoginRequest
import dev.sumin.skeleton.auth.config.AuthProperties
import dev.sumin.skeleton.auth.jwt.JwtTokenService
import dev.sumin.skeleton.common.ApplicationException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder

class PasswordLoginServiceTest {
    /** 호출 횟수를 세는 인코더 — 계정이 없을 때도 같은 일(해시 비교 한 번)을 하는지 본다 */
    private class CountingEncoder(private val delegate: PasswordEncoder = BCryptPasswordEncoder(4)) : PasswordEncoder {
        var matches = 0
        var upgradeAnswer = false
        var encodes = 0
        override fun encode(raw: CharSequence?): String? { encodes++; return delegate.encode(raw) }
        override fun matches(raw: CharSequence?, encoded: String?): Boolean { matches++; return delegate.matches(raw, encoded) }
        override fun upgradeEncoding(encoded: String?): Boolean = upgradeAnswer
    }

    private class Repo(val accounts: MutableList<AuthAccount>) : AuthAccountRepository {
        val upgrades = mutableListOf<Pair<String, String>>()
        override fun findBy(identifier: AccountIdentifier): AuthAccount? =
            accounts.firstOrNull { it.accountId == identifier.accountId || it.email == identifier.email || it.username == identifier.username }
        override val storesUpgradedPasswordHash: Boolean = true
        override fun upgradePasswordHash(accountId: String, newHash: String) { upgrades += accountId to newHash }
    }

    /** 자기 저장소를 가진 `auth` 만 쓰는 앱 — 새 해시를 받을 길이 없다 */
    private class OwnRepo(val accounts: MutableList<AuthAccount>) : AuthAccountRepository {
        override fun findBy(identifier: AccountIdentifier): AuthAccount? = accounts.firstOrNull { it.email == identifier.email }
    }

    private class RecordingHooks : LoginHooks {
        val events = CopyOnWriteArrayList<String>()
        var deny: ApplicationException? = null
        override fun beforeAttempt(attempt: LoginAttempt) { events += "before:${attempt.identifier}:${attempt.clientIp}"; deny?.let { throw it } }
        override fun onFailure(attempt: LoginAttempt, reason: LoginFailure, account: AuthAccount?) { events += "failure:$reason:${account?.accountId}" }
        override fun onSuccess(attempt: LoginAttempt, account: AuthAccount) { events += "success:${account.accountId}" }
    }

    private val encoder = CountingEncoder()
    private val hash = encoder.encode("correct-horse")!!
    private val repo = Repo(mutableListOf(AuthAccount("acc_1", "ann@example.com", "ann@example.com", hash, setOf("USER"))))
    private val hooks = RecordingHooks()
    private val jwt = JwtTokenService(AuthProperties.Jwt(secret = "test-jwt-secret-change-me-32-bytes"))
    private val service = PasswordLoginService(repo, encoder, AuthTokenResponseFactory(jwt)) { listOf(hooks) }

    private fun request(email: String = "ann@example.com", password: String = "correct-horse") = PasswordLoginRequest(email = email, password = password)

    @Test
    fun `unknown account still costs one hash comparison and fails like a wrong password`() {
        val before = encoder.matches
        assertFailsWith<InvalidCredentialsException> { service.login(request(email = "nobody@example.com"), "1.2.3.4") }
        assertEquals(1, encoder.matches - before, "an unknown account must do the same hash work as a known one")

        val beforeKnown = encoder.matches
        assertFailsWith<InvalidCredentialsException> { service.login(request(password = "wrong"), "1.2.3.4") }
        assertEquals(1, encoder.matches - beforeKnown)
    }

    @Test
    fun `account without a password hash cannot log in by password and does not blow up the encoder`() {
        repo.accounts[0] = repo.accounts[0].copy(passwordHash = "")
        assertFailsWith<InvalidCredentialsException> { service.login(request(), null) }
    }

    @Test
    fun `hooks see the attempt before the lookup and the outcome after`() {
        service.login(request(), "9.9.9.9")
        assertFailsWith<InvalidCredentialsException> { service.login(request(password = "x"), "9.9.9.9") }
        assertFailsWith<InvalidCredentialsException> { service.login(request(email = "who@example.com"), "9.9.9.9") }
        assertEquals(
            listOf(
                "before:email:ann@example.com:9.9.9.9", "success:acc_1",
                "before:email:ann@example.com:9.9.9.9", "failure:BAD_PASSWORD:acc_1",
                "before:email:who@example.com:9.9.9.9", "failure:UNKNOWN_ACCOUNT:null",
            ),
            hooks.events.toList(),
        )
    }

    @Test
    fun `a hook that denies stops the attempt before any password work`() {
        hooks.deny = ApplicationException("slow down", AuthErrorCode.TOO_MANY_ATTEMPTS)
        val before = encoder.matches
        val ex = assertFailsWith<ApplicationException> { service.login(request(), null) }
        assertEquals(AuthErrorCode.TOO_MANY_ATTEMPTS, ex.errorCode)
        assertEquals(before, encoder.matches)
    }

    @Test
    fun `a blocked account with the right password reports the block, with a wrong password it stays invalid credentials`() {
        repo.accounts[0] = repo.accounts[0].copy(loginBlock = LoginBlock.EMAIL_NOT_VERIFIED)
        val blocked = assertFailsWith<ApplicationException> { service.login(request(), null) }
        assertEquals(AuthErrorCode.EMAIL_NOT_VERIFIED, blocked.errorCode)
        assertTrue("failure:BLOCKED:acc_1" in hooks.events)
        assertFailsWith<InvalidCredentialsException> { service.login(request(password = "nope"), null) }
    }

    @Test
    fun `a successful login re-hashes when the encoder asks for an upgrade`() {
        encoder.upgradeAnswer = true
        service.login(request(), null)
        assertEquals(1, repo.upgrades.size)
        assertEquals("acc_1", repo.upgrades.single().first)
        assertTrue(encoder.matches("correct-horse", repo.upgrades.single().second))
    }

    @Test
    fun `no upgrade when the encoder is content with the hash`() {
        service.login(request(), null)
        assertTrue(repo.upgrades.isEmpty())
    }

    @Test
    fun `a password longer than bcrypt can take is just a wrong password`() {
        assertFailsWith<InvalidCredentialsException> { service.login(request(password = "x".repeat(200)), null) }
    }

    @Test
    fun `a repository that cannot store a new hash is not made to compute one on every login`() {
        encoder.upgradeAnswer = true
        val own = PasswordLoginService(OwnRepo(mutableListOf(AuthAccount("acc_1", "ann@example.com", "ann@example.com", hash, setOf("USER")))), encoder, AuthTokenResponseFactory(jwt))
        val before = encoder.encodes
        repeat(3) { own.login(request(), null) }
        assertEquals(0, encoder.encodes - before, "a bcrypt hash computed and thrown away on every login")
    }
}
