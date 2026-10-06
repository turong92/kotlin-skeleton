package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.abuse.AccountTaskRunner
import dev.sumin.skeleton.account.abuse.RateLimitedException
import dev.sumin.skeleton.account.challenge.ChallengePurposes
import dev.sumin.skeleton.account.events.AccountEventType
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.account.token.TokenPurposes
import dev.sumin.skeleton.common.ApplicationException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 가입 확인 = 가입 시도에 묶인 6자리 코드 (docs/accounts.md 위협 모델 "인증을 통한 사전 탈취").
 * 시도마다 자기 비밀번호 · 자기 코드가 있고, 시도가 확인되기 전에는 계정의 어떤 자격도 쓰이지 않는다 — 가입을 시작한 그 브라우저만 끝낼 수 있다.
 */
class SignUpVerificationTest {
    private val attackerPassword = "attacker-chosen-42"
    private val victimPassword = "moonlit-orchard-77"

    private fun err(block: () -> Unit) = assertFailsWith<ApplicationException> { block() }
    private fun AccountHarness.hashOf(email: String) = repo.findIdentity("password", email)?.secret
    private fun AccountHarness.passwordWorks(email: String, password: String) = hashOf(email)?.let { hasher.matches(password, it) } ?: false
    private fun AccountHarness.codes(email: String) = mailer.sent.filter { it.kind == MailKind.VERIFY_CODE && it.to == email }.map { it.vars.getValue("code") }
    private fun AccountHarness.wrongCodeFor(code: String) = if (code == "000000") "000001" else "000000"

    @Test
    fun `sign-up creates no account - only an attempt - and mails a six digit code, never a link`() {
        val h = AccountHarness()
        val outcome = h.signUp("ann@example.com", victimPassword)
        assertEquals(SignUpStatus.VERIFICATION_SENT, outcome.status)
        assertNotNull(outcome.signUpId)
        assertNull(h.repo.findByEmail("ann@example.com"), "nothing about the account is written before the attempt is verified")
        assertNull(h.repo.findIdentity("password", "ann@example.com"))
        val mail = h.mailer.of(MailKind.VERIFY_CODE).single()
        assertTrue(mail.vars.getValue("code").matches(Regex("\\d{6}")))
        assertNull(mail.link, "a password sign-up is never completed by a clickable link")
        val row = h.challengeStore.find(h.challenges.idOf(outcome.signUpId!!)!!)!!
        assertTrue(row.secret!!.startsWith("{bcrypt}") && victimPassword !in row.secret!!)
    }

    @Test
    fun `the right code creates the verified account with this attempt's password, signs in and spends the attempt`() {
        val h = AccountHarness()
        h.signUp("ann@example.com", victimPassword)
        val auth = h.verify("ann@example.com")
        val account = h.repo.findByEmail("ann@example.com")!!
        assertEquals(account.id, auth.accountId)
        assertEquals(AccountStatus.ACTIVE, account.status)
        assertTrue(account.emailVerified)
        assertEquals("Ann", account.displayName)
        assertEquals("Asia/Seoul", account.timeZone)
        assertTrue(h.repo.findIdentity("password", "ann@example.com")!!.verified)
        assertTrue(h.passwordWorks("ann@example.com", victimPassword))
        assertNull(auth.loginBlock)
        assertTrue(AccountEventType.SIGN_UP in h.events.types() && AccountEventType.EMAIL_VERIFIED in h.events.types())
        // replay: the used code (and the attempt) is dead
        assertEquals("ACCOUNT.CODE_EXPIRED", err { h.registration.verifyEmail(h.signUpIds.getValue("ann@example.com"), h.lastCode(), "203.0.113.1") }.errorCode.code)
    }

    @Test
    fun `pre-hijack - the attacker's attempt cannot be finished by anyone but the attacker, and the victim ends with the victim's password`() {
        val h = AccountHarness()
        val attacker = h.signUp("victim@example.com", attackerPassword)           // 1. attacker starts an attempt for the victim's address
        val attackerCode = h.lastCode("victim@example.com")                        //    the code goes to the VICTIM's mailbox
        val victim = h.signUp("victim@example.com", victimPassword)                // 2. the victim signs up too: own attempt, own code, nothing overwritten
        val victimCode = h.lastCode("victim@example.com")
        assertNotEquals(attacker.signUpId, victim.signUpId)

        // 3. the victim types ANY code from the mailbox into the victim's own page: only the code of that attempt works
        if (attackerCode != victimCode) {
            val e = err { h.registration.verifyEmail(victim.signUpId!!, attackerCode, "203.0.113.2") }
            assertEquals("ACCOUNT.CODE_INVALID", e.errorCode.code)
        }
        h.registration.verifyEmail(victim.signUpId!!, victimCode, "203.0.113.2")
        assertTrue(h.passwordWorks("victim@example.com", victimPassword))
        assertEquals(false, h.passwordWorks("victim@example.com", attackerPassword), "4. the attacker's password never became a credential")
        // the attacker can no longer finish: the attempt was discarded, and a guess cannot create anything
        assertEquals("ACCOUNT.CODE_EXPIRED", err { h.registration.verifyEmail(attacker.signUpId!!, attackerCode, "198.51.100.1") }.errorCode.code)
        assertTrue(h.passwordWorks("victim@example.com", victimPassword))
    }

    @Test
    fun `an attacker who never sees the code cannot finish their own attempt - guesses are capped and the attempt dies`() {
        val h = AccountHarness()
        val attacker = h.signUp("victim@example.com", attackerPassword)
        val real = h.lastCode("victim@example.com")
        val wrong = h.wrongCodeFor(real)
        for (left in listOf(4, 3, 2, 1)) {
            val e = err { h.registration.verifyEmail(attacker.signUpId!!, wrong, "198.51.100.1") }
            assertEquals("ACCOUNT.CODE_INVALID", e.errorCode.code)
            assertEquals(mapOf("attemptsLeft" to left), e.data)
        }
        assertEquals("ACCOUNT.CODE_EXPIRED", err { h.registration.verifyEmail(attacker.signUpId!!, wrong, "198.51.100.1") }.errorCode.code, "the fifth wrong guess ends the attempt")
        assertEquals("ACCOUNT.CODE_EXPIRED", err { h.registration.verifyEmail(attacker.signUpId!!, real, "198.51.100.1") }.errorCode.code, "even the right code is dead now")
        assertNull(h.repo.findByEmail("victim@example.com"))
    }

    @Test
    fun `pre-hijack through resend - the new code belongs to the same attempt, so the victim's own page yields the victim's password`() {
        val h = AccountHarness(AccountProperties(verification = AccountProperties.Verification(perEmail = 100), mail = AccountProperties.Mail(linkBaseUrl = "https://x"), password = AccountProperties.Password(bcryptStrength = 4)))
        val attacker = h.signUp("victim@example.com", attackerPassword)
        val victim = h.signUp("victim@example.com", victimPassword)
        h.time.advance(Duration.ofSeconds(31))
        h.registration.resendVerification(attacker.signUpId!!, "198.51.100.1", null)     // the attacker presses resend: another code to the victim's mailbox
        val attackersNewCode = h.lastCode("victim@example.com")
        h.time.advance(Duration.ofSeconds(31))
        h.registration.resendVerification(victim.signUpId!!, "203.0.113.2", null)        // the victim presses resend on the victim's page
        val victimsNewCode = h.lastCode("victim@example.com")
        assertNotEquals(attackersNewCode, victimsNewCode)
        h.registration.verifyEmail(victim.signUpId!!, victimsNewCode, "203.0.113.2")
        assertTrue(h.passwordWorks("victim@example.com", victimPassword))
        assertEquals(false, h.passwordWorks("victim@example.com", attackerPassword))
    }

    @Test
    fun `pre-hijack of the bootstrap admin - ADMIN goes to the account whose password the mailbox owner typed`() {
        val h = AccountHarness(
            AccountProperties(
                bootstrap = AccountProperties.Bootstrap(adminEmail = "boss@example.com"),
                mail = AccountProperties.Mail(linkBaseUrl = "https://app.example.com"), password = AccountProperties.Password(bcryptStrength = 4),
            ),
        )
        h.signUp("boss@example.com", attackerPassword)
        h.signUp("boss@example.com", victimPassword)
        h.verify("boss@example.com")                                                  // the owner's own attempt, the owner's code
        assertTrue("ADMIN" in h.repo.findByEmail("boss@example.com")!!.roles)
        assertTrue(h.passwordWorks("boss@example.com", victimPassword))
        assertEquals(false, h.passwordWorks("boss@example.com", attackerPassword))
    }

    @Test
    fun `two attempts for one address that both hold a right code - exactly one wins, deterministically, the other is expired`() {
        repeat(25) {
            val h = AccountHarness()
            val a = h.signUp("ann@example.com", "first-typed-pass-11")
            val codeA = h.lastCode("ann@example.com")
            val b = h.signUp("ann@example.com", "second-typed-pass-22")
            val codeB = h.lastCode("ann@example.com")
            val pool = Executors.newFixedThreadPool(2)
            val go = CountDownLatch(1)
            val jobs = listOf(a.signUpId!! to codeA, b.signUpId!! to codeB)
            val results = jobs.map { (id, code) -> pool.submit<Result<Unit>> { go.await(); runCatching { h.registration.verifyEmail(id, code, "203.0.113.5"); Unit } } }
            go.countDown()
            val outcomes = results.map { it.get() }
            pool.shutdown()
            assertEquals(1, outcomes.count { it.isSuccess })
            val winner = listOf("first-typed-pass-11", "second-typed-pass-22")[outcomes.indexOfFirst { it.isSuccess }]
            assertEquals("ACCOUNT.CODE_EXPIRED", (outcomes.first { it.isFailure }.exceptionOrNull() as ApplicationException).errorCode.code)
            assertTrue(h.passwordWorks("ann@example.com", winner))
            assertEquals(1, h.repo.search(null, null, 0, 10).total)
            assertEquals(0, h.challengeStore.findOpen(ChallengePurposes.SIGN_UP, "ann@example.com", h.time.now())?.let { 1 } ?: 0, "the other attempts of the address were discarded")
        }
    }

    @Test
    fun `a code that is wrong or typed with the wrong attempt id never matches, and an unknown or expired attempt is one answer`() {
        val h = AccountHarness()
        val a = h.signUp("ann@example.com", victimPassword)
        assertEquals("ACCOUNT.CODE_EXPIRED", err { h.registration.verifyEmail("x".repeat(43), "123456", "203.0.113.1") }.errorCode.code)
        assertEquals("ACCOUNT.CODE_EXPIRED", err { h.registration.verifyEmail("short", "123456", "203.0.113.1") }.errorCode.code)
        h.time.advance(Duration.ofMinutes(11))
        assertEquals("ACCOUNT.CODE_EXPIRED", err { h.registration.verifyEmail(a.signUpId!!, h.lastCode("ann@example.com"), "203.0.113.1") }.errorCode.code)
        assertNull(h.repo.findByEmail("ann@example.com"))
    }

    @Test
    fun `an already registered address looks the same - same outcome, an 'already registered' mail instead of a code, and a code that never works`() {
        val h = AccountHarness()
        h.activeAccount("ann@example.com", victimPassword)
        h.mailer.sent.clear()
        val outcome = h.signUp("ann@example.com", attackerPassword)
        assertEquals(SignUpStatus.VERIFICATION_SENT, outcome.status)
        assertNotNull(outcome.signUpId)
        assertEquals(0, h.mailer.of(MailKind.VERIFY_CODE).size, "no code is sent to a registered address")
        assertEquals(1, h.mailer.of(MailKind.ALREADY_REGISTERED).size)
        // entering codes behaves exactly like a wrong guess on a new address: the countdown, then expired
        for (left in listOf(4, 3, 2, 1)) assertEquals(mapOf("attemptsLeft" to left), err { h.registration.verifyEmail(outcome.signUpId!!, "123456", "198.51.100.1") }.data)
        assertEquals("ACCOUNT.CODE_EXPIRED", err { h.registration.verifyEmail(outcome.signUpId!!, "123456", "198.51.100.1") }.errorCode.code)
        assertTrue(h.passwordWorks("ann@example.com", victimPassword))
        assertEquals(false, h.passwordWorks("ann@example.com", attackerPassword))
    }

    @Test
    fun `enumeration parity - the request thread does the same work for a new, an in-flight and a registered address, and only the mail differs behind it`() {
        val queue = LinkedBlockingQueue<Runnable>()
        val h = AccountHarness(tasks = AccountTaskRunner { _, task -> queue.add(task) })
        // registered
        h.signUp("known@example.com", victimPassword); queue.forEach { it.run() }; queue.clear(); h.verify("known@example.com")
        val requestThread = Thread.currentThread()
        val shapes = listOf("new@example.com", "known@example.com", "new@example.com").map { email ->
            h.callLog.calls.clear()
            val o = h.signUp(email, victimPassword)
            Triple(o.status, o.signUpId?.length, h.callLog.by(requestThread))
        }
        assertEquals(1, shapes.map { it.first }.toSet().size)
        assertEquals(1, shapes.map { it.second }.toSet().size, "the attempt id has the same shape")
        assertEquals(listOf(emptyList<String>()), shapes.map { it.third }.toSet().toList(), "the request thread never reads the account store - registered or not")
    }

    @Test
    fun `a new code for the same attempt - needs the cooldown, is capped, replaces the old one and is silent for unknown or expired attempts`() {
        val h = AccountHarness()
        val o = h.signUp("ann@example.com", victimPassword)
        val first = h.lastCode("ann@example.com")
        h.registration.resendVerification(o.signUpId!!, "203.0.113.1", null)
        assertEquals(1, h.codes("ann@example.com").size, "inside the cooldown nothing is sent")
        h.time.advance(Duration.ofSeconds(31))
        h.registration.resendVerification(o.signUpId!!, "203.0.113.1", null)
        assertEquals(2, h.codes("ann@example.com").size)
        val second = h.lastCode("ann@example.com")
        if (first != second) assertEquals("ACCOUNT.CODE_INVALID", err { h.registration.verifyEmail(o.signUpId!!, first, "203.0.113.1") }.errorCode.code, "the old code is replaced")
        h.registration.resendVerification("x".repeat(43), "203.0.113.1", null)        // unknown id: same silence
        assertEquals(2, h.codes("ann@example.com").size)
        h.registration.verifyEmail(o.signUpId!!, second, "203.0.113.1")
        assertNotNull(h.repo.findByEmail("ann@example.com"))
    }

    @Test
    fun `at most three resends per attempt`() {
        val h = AccountHarness(AccountProperties(verification = AccountProperties.Verification(perEmail = 100), mail = AccountProperties.Mail(linkBaseUrl = "https://x"), password = AccountProperties.Password(bcryptStrength = 4)))
        val o = h.signUp("ann@example.com", victimPassword)
        repeat(5) { h.time.advance(Duration.ofSeconds(31)); h.registration.resendVerification(o.signUpId!!, "203.0.113.1", null) }
        assertEquals(4, h.codes("ann@example.com").size, "the first mail plus three resends")
    }

    @Test
    fun `mails to one address are budgeted but the attempt is still stored and answered the same`() {
        val h = AccountHarness()   // perEmail = 3
        val ids = (1..4).map { h.signUp("ann@example.com", victimPassword).signUpId!! }
        assertEquals(3, h.codes("ann@example.com").size, "the fourth code is not mailed")
        assertTrue(ids.all { h.challengeStore.find(h.challenges.idOf(it)!!) != null }, "but all four attempts exist")
    }

    @Test
    fun `an address can open only a few attempts per hour - the total of guesses against one address is bounded, the answer is not`() {
        val h = AccountHarness(AccountProperties(verification = AccountProperties.Verification(signUpAttemptsPerEmail = 2, perEmail = 100), mail = AccountProperties.Mail(linkBaseUrl = "https://x"), password = AccountProperties.Password(bcryptStrength = 4)))
        val ids = (1..3).map { h.signUp("ann@example.com", victimPassword) }
        assertTrue(ids.all { it.status == SignUpStatus.VERIFICATION_SENT && it.signUpId != null })
        assertEquals("ACCOUNT.CODE_EXPIRED", err { h.registration.verifyEmail(ids[2].signUpId!!, h.lastCode("ann@example.com"), "203.0.113.1") }.errorCode.code, "the third attempt was never stored")
        assertTrue(h.registration.verifyEmail(ids[1].signUpId!!, h.codes("ann@example.com")[1], "203.0.113.1").accountId.isNotEmpty())
    }

    @Test
    fun `code entries are rate limited per IP, loudly`() {
        val h = AccountHarness(AccountProperties(verification = AccountProperties.Verification(attemptsPerIp = 3), mail = AccountProperties.Mail(linkBaseUrl = "https://x"), password = AccountProperties.Password(bcryptStrength = 4)))
        val o = h.signUp("ann@example.com", victimPassword)
        repeat(3) { assertEquals("ACCOUNT.CODE_INVALID", err { h.registration.verifyEmail(o.signUpId!!, h.wrongCodeFor(h.lastCode("ann@example.com")), "198.51.100.1") }.errorCode.code) }
        assertFailsWith<RateLimitedException> { h.registration.verifyEmail(o.signUpId!!, h.lastCode("ann@example.com"), "198.51.100.1") }
        h.registration.verifyEmail(o.signUpId!!, h.lastCode("ann@example.com"), "198.51.100.2")   // another IP is not affected
    }

    @Test
    fun `verifying discards the other attempts and closes magic links for the address and keeps sessions clean`() {
        val h = AccountHarness()
        h.signUp("ann@example.com", attackerPassword)
        val mine = h.signUp("ann@example.com", victimPassword)
        val magic = h.tokens.issue(TokenPurposes.MAGIC_LINK, "ann@example.com", null, Duration.ofHours(1))
        h.registration.verifyEmail(mine.signUpId!!, h.lastCode("ann@example.com"), "203.0.113.1")
        assertNull(h.tokens.peek(TokenPurposes.MAGIC_LINK, magic))
        assertNull(h.challengeStore.findOpen(ChallengePurposes.SIGN_UP, "ann@example.com", h.time.now()))
    }

    @Test
    fun `an unverified account that already exists (an app that turned verification on later) is proven by the code and gets this attempt's password only`() {
        val h = AccountHarness(AccountProperties(signUp = AccountProperties.SignUp(emailVerification = false), mail = AccountProperties.Mail(linkBaseUrl = "https://x"), password = AccountProperties.Password(bcryptStrength = 4)))
        h.signUp("ann@example.com", attackerPassword)
        val planted = h.repo.findByEmail("ann@example.com")!!
        h.identitiesPlanted(planted.id)
        val verifying = AccountHarness(AccountProperties(mail = AccountProperties.Mail(linkBaseUrl = "https://x"), password = AccountProperties.Password(bcryptStrength = 4)), storage = h.repo)
        val o = verifying.signUp("ann@example.com", victimPassword)
        verifying.registration.verifyEmail(o.signUpId!!, verifying.lastCode("ann@example.com"), "203.0.113.1")
        val account = verifying.repo.findByEmail("ann@example.com")!!
        assertEquals(planted.id, account.id)
        assertTrue(verifying.passwordWorks("ann@example.com", victimPassword))
        assertEquals(listOf("password"), verifying.repo.identitiesOf(account.id).map { it.method }, "everything planted before the proof is gone")
    }

    private fun AccountHarness.identitiesPlanted(accountId: String) {
        repo.addIdentity(Identity(core.newIdentityId(), accountId, "google", "g-planted", true, createdAt = time.now()))
    }

    @Test
    fun `without email verification the account is active at once and a duplicate is a 409 - no attempt, no id`() {
        val h = AccountHarness(AccountProperties(signUp = AccountProperties.SignUp(emailVerification = false), mail = AccountProperties.Mail(linkBaseUrl = "https://x"), password = AccountProperties.Password(bcryptStrength = 4)))
        val o = h.signUp("ann@example.com", victimPassword)
        assertEquals(SignUpStatus.CREATED, o.status)
        assertNull(o.signUpId)
        assertEquals("ACCOUNT.EMAIL_TAKEN", err { h.signUp("ann@example.com", victimPassword) }.errorCode.code)
    }
}
