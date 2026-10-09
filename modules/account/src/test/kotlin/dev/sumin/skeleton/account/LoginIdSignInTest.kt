package dev.sumin.skeleton.account

import dev.sumin.skeleton.auth.account.AccountIdentifier
import dev.sumin.skeleton.auth.account.LoginBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** B5 — 로그인 칸의 `username` 이 아이디일 때 `AccountAuthRepository.findBy` 가 읽는 길 (스펙 §7 · §5.4) */
class LoginIdSignInTest {
    private fun props(enabled: Boolean = true, selfRestore: Boolean = false) = AccountProperties(
        mail = AccountProperties.Mail(linkBaseUrl = "https://app.example.com"), password = AccountProperties.Password(bcryptStrength = 4),
        loginId = AccountProperties.LoginId(enabled = enabled), deletion = AccountProperties.Deletion(selfRestore = selfRestore),
    )

    private fun AccountHarness.member(email: String = "ann@example.com", loginId: String = "Ann.Kim"): Account {
        registration.signUp(SignUpCommand(email, "tangerine-42-moon", null, "ko", "Asia/Seoul", "203.0.113.1", null, loginId = loginId))
            .also { o -> signUpIds[Emails.normalize(email)] = o.signUpId!! }
        registration.verifyEmail(signUpIds.getValue(Emails.normalize(email)), lastCode(email), null)
        return repo.findByEmail(Emails.normalize(email))!!
    }

    /** 소셜로만 들어온 계정 — 이메일은 있고 비밀번호 수단은 없다 */
    private fun AccountHarness.socialOnly(id: String = "acc_social", loginId: String? = "social.one", email: String? = "soc@example.com"): Account {
        val now = time.now()
        val a = Account(id, email, true, AccountStatus.ACTIVE, setOf("USER"), null, "ko", "Asia/Seoul", now, now, loginId = loginId)
        repo.insert(a, listOf(Identity("idn_$id", id, "google", "g-$id", true, createdAt = now)), core.loginIdHashOf(loginId))
        return a
    }

    private fun AccountHarness.find(username: String) = authRepository.findBy(AccountIdentifier(username = username))

    @Test
    fun `a login id signs in with any letter case and surrounding spaces`() {
        val h = AccountHarness(props())
        val a = h.member()
        listOf("ann.kim", "ANN.KIM", "  Ann.Kim ").forEach { assertEquals(a.id, h.find(it)?.accountId, it) }
    }

    @Test
    fun `dots and hyphens inside an id are still an id`() {
        val h = AccountHarness(props())
        val dotted = h.member("a@example.com", "sumin.kim")
        val hyphen = h.member("b@example.com", "sumin-kim2")
        assertEquals(dotted.id, h.find("Sumin.Kim")?.accountId)
        assertEquals(hyphen.id, h.find("sumin-kim2")?.accountId)
    }

    @Test
    fun `an at sign sends the username down the email road even when on`() {
        val h = AccountHarness(props())
        val a = h.member()
        assertEquals(a.id, h.find("ANN@example.com")?.accountId)
        assertNull(h.find("ann.kim@example.com"), "the id plus a domain is just an unknown email")
    }

    @Test
    fun `the auth account keeps username as the email and carries the password hash`() {
        val h = AccountHarness(props())
        val a = h.member()
        val auth = assertNotNull(h.find("ann.kim"))
        assertEquals("ann@example.com", auth.username, "the id is not the token's username")
        assertEquals("ann@example.com", auth.email)
        assertEquals(a.id, auth.accountId)
        assert(auth.passwordHash.isNotEmpty())
    }

    @Test
    fun `an unknown id is no account`() {
        val h = AccountHarness(props())
        h.member()
        assertNull(h.find("nobody.here"))
    }

    @Test
    fun `off - the username is read as an email, exactly as before`() {
        val h = AccountHarness(props(enabled = false))
        val a = h.activeAccount()
        assertEquals(a.id, h.find("ann@example.com")?.accountId)
        assertNull(h.find("ann.kim"))
        h.socialOnly(loginId = "social.one")
        assertNull(h.find("social.one"), "a stored id is not read while the option is off")
        assertEquals("acc_social", h.find("soc@example.com")?.accountId)
    }

    @Test
    fun `a social-only account is found by its id with no password hash, so the login does the dummy comparison`() {
        val h = AccountHarness(props())
        h.socialOnly()
        val auth = assertNotNull(h.find("Social.One"))
        assertEquals("", auth.passwordHash)
    }

    @Test
    fun `a social-only account without an email is found by id too`() {
        val h = AccountHarness(props())
        h.socialOnly(id = "acc_noemail", loginId = "noemail.one", email = null)
        val auth = assertNotNull(h.find("noemail.one"))
        assertEquals("acc_noemail", auth.username)
        assertEquals("", auth.passwordHash)
    }

    @Test
    fun `a suspended account's id gives the suspended block, like its email`() {
        val h = AccountHarness(props())
        val a = h.member()
        h.repo.update(a.id, AccountPatch(status = AccountStatus.SUSPENDED), h.time.now())
        assertEquals(LoginBlock.SUSPENDED, h.find("ann.kim")?.loginBlock)
        assertEquals(LoginBlock.SUSPENDED, h.find("ann@example.com")?.loginBlock)
    }

    @Test
    fun `a deleted account's id is no account, or the pending state with self-restore - like its email`() {
        val off = AccountHarness(props(selfRestore = false))
        val a = off.member()
        off.deletion.delete(a.id, ReauthInput("tangerine-42-moon"), null)
        assertNull(off.find("ann.kim"))
        assertNull(off.find("ann@example.com"))

        val on = AccountHarness(props(selfRestore = true))
        val b = on.member()
        on.deletion.delete(b.id, ReauthInput("tangerine-42-moon"), null)
        assertEquals(LoginBlock.DELETION_PENDING, on.find("ann.kim")?.loginBlock)
        assertEquals(LoginBlock.DELETION_PENDING, on.find("ann@example.com")?.loginBlock)
    }

    @Test
    fun `an erased account's id is gone`() {
        val h = AccountHarness(props())
        val a = h.member()
        h.deletion.delete(a.id, ReauthInput("tangerine-42-moon"), null)
        h.time.advance(java.time.Duration.ofDays(31))
        assertEquals(1, h.purge.purgeDue())
        assertNull(h.find("ann.kim"))
    }

    @Test
    fun `a store that folds letter case still only answers when the stored id equals the normalised input literally`() {
        val real = InMemoryAccountRepository()
        val folding = object : AccountRepository by real {
            override fun findByLoginId(loginId: String): Account? = real.findByLoginId("ann.kim")?.copy(loginId = "Ann.Kim")
        }
        val h = AccountHarness(props(), storage = folding)
        h.member()
        assertNull(h.find("ann.kim"), "stored 'Ann.Kim' is not the normalised 'ann.kim'")
    }
}
