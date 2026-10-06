package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.abuse.LoginThrottle
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.account.token.TokenPurposes
import dev.sumin.skeleton.auth.account.AccountIdentifier
import dev.sumin.skeleton.auth.login.LoginAttempt
import dev.sumin.skeleton.common.ApplicationException
import java.text.Normalizer
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 이메일 규칙은 한 곳이다 (`Emails.normalize`: 앞뒤 공백 제거 · 유니코드 NFC · 소문자(Locale.ROOT) — 점 · `+태그` 는 건드리지 않는다).
 * 그리고 같은 주소인지는 **저장된 글자가 정규화된 입력과 같은지**로 정한다 — DB 정렬이 느슨해도 남의 계정이 내려오지 않는다.
 */
class EmailNormalizationTest {
    /** 악센트 · 대소문자를 같게 보는 정렬(MySQL 기본 `utf8mb4_0900_ai_ci`)로 찾는 저장소 — 유니크 키는 정확 일치로 둔다 */
    private class LooseCollationRepository(private val inner: InMemoryAccountRepository = InMemoryAccountRepository()) : AccountRepository by inner {
        private fun fold(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
        override fun findByEmail(email: String): Account? = inner.search(null, null, 0, 1000).items.firstOrNull { it.email != null && fold(it.email!!) == fold(email) }
    }

    private fun looseHarness() = AccountHarness(storage = LooseCollationRepository())

    @Test
    fun `one rule - trim, NFC and root-locale lower case, nothing else`() {
        assertEquals("ann@example.com", Emails.normalize("  Ann@EXAMPLE.com \n"))
        assertEquals(Emails.normalize("mäil@example.com"), Emails.normalize("mäil@example.com"), "composed and decomposed are one address")
        assertEquals("ann+tag@example.com", Emails.normalize("Ann+Tag@Example.com"), "plus tags and dots are the mailbox owner's business")
        assertEquals("i@example.com", Emails.normalize("I@Example.com"), "the Turkish dotted I must not change the result: Locale.ROOT")
    }

    @Test
    fun `a look-alike address does not find the victim's account - login lookup`() {
        val h = looseHarness()
        h.activeAccount("victim@gmail.com")
        assertNull(h.authRepository.findBy(AccountIdentifier(email = "victim@gmäil.com")))
        assertNotNull(h.authRepository.findBy(AccountIdentifier(email = "  VICTIM@gmail.com ")))
    }

    @Test
    fun `a look-alike forgot request mails nobody and leaves the victim's open reset link alone`() {
        val h = looseHarness()
        h.activeAccount("victim@gmail.com")
        h.passwords.forgot("victim@gmail.com", "203.0.113.1", null)
        val link = h.mailer.of(MailKind.PASSWORD_RESET).single()
        h.mailer.sent.clear()

        h.passwords.forgot("victim@gmäil.com", "203.0.113.2", null)

        assertEquals(0, h.mailer.sent.size, "no mail to the look-alike string and none to the victim")
        assertNotNull(h.tokens.peek(TokenPurposes.PASSWORD_RESET, h.mailer.tokenOf(link)), "a third party closed the victim's link")
    }

    @Test
    fun `signing up with a look-alike address creates its own account and mails only that address`() {
        val h = looseHarness()
        h.activeAccount("victim@gmail.com")
        h.mailer.sent.clear()
        h.signUp("victim@gmäil.com")
        assertEquals(setOf("victim@gmäil.com"), h.mailer.sent.map { it.to }.toSet())
        assertEquals(MailKind.VERIFY_EMAIL, h.mailer.sent.single().kind)
    }

    @Test
    fun `a mail always goes to the address stored on the account`() {
        val h = AccountHarness()
        val a = h.activeAccount("ann@example.com")
        h.mailer.sent.clear()
        h.passwords.forgot("  ANN@Example.COM ", "203.0.113.1", null)
        assertEquals(listOf(a.email), h.mailer.sent.map { it.to })
    }

    @Test
    fun `the login limit key is one per address however it is typed`() {
        val h = AccountHarness()
        val throttle = LoginThrottle(h.core)
        listOf("email:Ann@Example.com", "username:ann@example.com", "email: ann@example.com ", "username:ANN@EXAMPLE.COM").forEachIndexed { i, id ->
            repeat(if (i < 2) 5 else 0) { throttle.beforeAttempt(LoginAttempt(id, "198.51.100.${i * 10 + it}")) }
        }
        assertFailsWith<ApplicationException> { throttle.beforeAttempt(LoginAttempt("email: ann@example.com ", "198.51.100.200")) }
    }

    @Test
    fun `an account id login shares the bucket of the account's address`() {
        val h = AccountHarness()
        val a = h.activeAccount("ann@example.com")
        val throttle = LoginThrottle(h.core)
        repeat(5) { throttle.beforeAttempt(LoginAttempt("accountId:${a.id}", "198.51.100.$it")) }
        repeat(5) { throttle.beforeAttempt(LoginAttempt("email:ann@example.com", "198.51.100.${10 + it}")) }
        assertFailsWith<ApplicationException> { throttle.beforeAttempt(LoginAttempt("email:ann@example.com", "198.51.100.99")) }
        assertTrue(h.time.now() != null)
    }

    @Test
    fun `window helper keeps the harness honest`() {
        assertEquals(Duration.ofHours(1), AccountHarness().oneHour())
    }
}
