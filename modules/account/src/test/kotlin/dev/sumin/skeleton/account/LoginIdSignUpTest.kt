package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.abuse.AccountCaptcha
import dev.sumin.skeleton.account.events.AccountEventType
import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.FieldValidationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** B3 — 가입 요청의 로그인 아이디: 순서(형식은 한도 앞, 중복 판정은 캡차 뒤), 같은 409, 확정 때의 겹침과 재시도, 꺼짐에서는 읽지 않는다 */
class LoginIdSignUpTest {
    private fun props(enabled: Boolean = true, perIp: Int = 30, emailVerification: Boolean = true, reserved: List<String> = listOf("admin")) = AccountProperties(
        mail = AccountProperties.Mail(linkBaseUrl = "https://app.example.com"), password = AccountProperties.Password(bcryptStrength = 4),
        signUp = AccountProperties.SignUp(perIp = perIp, emailVerification = emailVerification),
        loginId = AccountProperties.LoginId(enabled = enabled, reserved = reserved),
    )

    private fun AccountHarness.signUpWith(email: String, loginId: String?, ip: String? = "203.0.113.1", captchaToken: String? = null): SignUpOutcome =
        registration.signUp(SignUpCommand(email, "tangerine-42-moon", null, "ko", "Asia/Seoul", ip, captchaToken, loginId = loginId))
            .also { o -> o.signUpId?.let { signUpIds[Emails.normalize(email)] = it } }

    private fun AccountHarness.verifyWith(email: String, loginId: String? = null) =
        registration.verifyEmail(signUpIds.getValue(Emails.normalize(email)), lastCode(email), null, loginId = loginId)

    private fun codeOf(block: () -> Unit) = assertFailsWith<ApplicationException>(block = block).errorCode.code

    @Test
    fun `a good login id is stored lower-cased on the account created by the confirmation`() {
        val h = AccountHarness(props())
        h.signUpWith("ann@example.com", "  Ann.Kim  ")
        h.verifyWith("ann@example.com")
        assertEquals("ann.kim", h.repo.findByEmail("ann@example.com")!!.loginId)
        assertTrue(h.repo.loginIdUsed(h.core.blocks.loginIdHash("ann.kim")))
    }

    @Test
    fun `the form comes first - a bad id is a 400 on loginId, costs no allowance and no attempt`() {
        val h = AccountHarness(props(perIp = 1))
        assertEquals("Required", assertFailsWith<FieldValidationException> { h.signUpWith("a@example.com", null) }.fieldCode)
        assertEquals("Required", assertFailsWith<FieldValidationException> { h.signUpWith("a@example.com", "   ") }.fieldCode)
        assertEquals("Size", assertFailsWith<FieldValidationException> { h.signUpWith("a@example.com", "abc") }.fieldCode)
        assertEquals("Pattern", assertFailsWith<FieldValidationException> { h.signUpWith("a@example.com", "a..bcd") }.fieldCode)
        assertEquals("loginId", assertFailsWith<FieldValidationException> { h.signUpWith("a@example.com", "bad id") }.field)
        assertEquals(0, h.mailer.sent.size)
        h.signUpWith("one@example.com", "oneid")
        assertEquals("ACCOUNT.RATE_LIMITED", codeOf { h.signUpWith("two@example.com", "twoid") }, "the allowance of 1 was used by the real request only")
    }

    @Test
    fun `the availability check comes after the captcha - a refused captcha is answered before the id is looked at`() {
        var calls = 0
        var pass = true
        val h = AccountHarness(props(), captcha = AccountCaptcha { _, _, _ -> calls++; pass })
        h.signUpWith("owner@example.com", "takenid", captchaToken = "t"); h.verifyWith("owner@example.com")
        assertEquals("ACCOUNT.LOGIN_ID_TAKEN", codeOf { h.signUpWith("b@example.com", "takenid", captchaToken = "t") })
        assertEquals(2, calls, "the duplicate was found after the captcha was asked")
        pass = false
        assertNotEquals("ACCOUNT.LOGIN_ID_TAKEN", codeOf { h.signUpWith("c@example.com", "takenid", captchaToken = "t") }, "a failed captcha hides whether the id is free")
    }

    @Test
    fun `a reserved id (symbols ignored), a live id and an erased account's id give the same 409, whatever the email is`() {
        val h = AccountHarness(props())
        h.signUpWith("owner@example.com", "liveone"); h.verifyWith("owner@example.com")
        h.signUpWith("gone@example.com", "goneone"); h.verifyWith("gone@example.com")
        h.deletion.delete(h.repo.findByEmail("gone@example.com")!!.id, ReauthInput("tangerine-42-moon"), null)
        h.time.advance(java.time.Duration.ofDays(31))
        assertEquals(1, h.purge.purgeDue())
        assertNull(h.repo.findByLoginId("goneone"), "the account is gone, only the hash is left")
        listOf("ad.min", "AD_MIN", "liveone", "LiveOne", "goneone").forEach { id ->
            listOf("new@example.com", "owner@example.com").forEach { email ->
                assertEquals("ACCOUNT.LOGIN_ID_TAKEN", codeOf { h.signUpWith(email, id) }, "$id / $email")
            }
        }
        assertEquals(SignUpStatus.VERIFICATION_SENT, h.signUpWith("owner@example.com", "freeone").status, "a registered email with a free id is the same 202 as a new one")
    }

    @Test
    fun `the id travels in the attempt and is not mailed, logged in events or kept out of the attempt of an off app`() {
        val h = AccountHarness(props())
        h.signUpWith("ann@example.com", "annid"); h.verifyWith("ann@example.com")
        assertTrue(h.events.all.none { e -> e.detail.values.any { "annid" in it } || e.ip == "annid" }, "no event carries the id")
        assertTrue(h.mailer.sent.none { m -> "annid" in m.toString() })
        val set = h.events.all.single { it.type == AccountEventType.LOGIN_ID_SET }
        assertEquals(mapOf("via" to "sign_up"), set.detail)
        assertEquals(h.repo.findByEmail("ann@example.com")!!.id, set.accountId)
    }

    @Test
    fun `taken between the request and the confirmation - 409, the attempt stays alive, another id finishes it`() {
        val h = AccountHarness(props())
        h.signUpWith("late@example.com", "sameid")          // free at this moment
        h.signUpWith("early@example.com", "sameid")         // also free: attempts hold no reservation
        h.verifyWith("early@example.com")
        assertEquals("ACCOUNT.LOGIN_ID_TAKEN", codeOf { h.verifyWith("late@example.com") })
        assertNull(h.repo.findByEmail("late@example.com"), "no account was made")
        assertEquals("ACCOUNT.LOGIN_ID_TAKEN", codeOf { h.verifyWith("late@example.com", "sameid") }, "the same id again is the same answer, still not a dead attempt")
        assertEquals("ACCOUNT.LOGIN_ID_TAKEN", codeOf { h.verifyWith("late@example.com", "ADMIN") }, "a reserved replacement is refused too")
        assertEquals("loginId", assertFailsWith<FieldValidationException> { h.verifyWith("late@example.com", "x") }.field, "a malformed replacement is a 400 before anything is spent")
        h.verifyWith("late@example.com", "otherid")
        assertEquals("otherid", h.repo.findByEmail("late@example.com")!!.loginId)
        assertEquals("sameid", h.repo.findByEmail("early@example.com")!!.loginId)
    }

    @Test
    fun `without email verification the sign-up request is the confirmation and a taken id is the 409 at once`() {
        val h = AccountHarness(props(emailVerification = false))
        assertEquals(SignUpStatus.CREATED, h.signUpWith("a@example.com", "firstid").status)
        assertEquals("firstid", h.repo.findByEmail("a@example.com")!!.loginId)
        assertEquals("ACCOUNT.LOGIN_ID_TAKEN", codeOf { h.signUpWith("b@example.com", "firstid") })
        assertNull(h.repo.findByEmail("b@example.com"))
        assertEquals("ACCOUNT.LOGIN_ID_TAKEN", codeOf { h.signUpWith("c@example.com", "admin") })
    }

    @Test
    fun `the id is looked at before the password - a taken id with a weak password is the 409`() {
        val h = AccountHarness(props())
        h.signUpWith("owner@example.com", "ownerid"); h.verifyWith("owner@example.com")
        val cmd = SignUpCommand("x@example.com", "123", null, null, null, "203.0.113.9", null, loginId = "ownerid")
        assertEquals("ACCOUNT.LOGIN_ID_TAKEN", codeOf { h.registration.signUp(cmd) })
    }

    @Test
    fun `off - the id is not read at all - garbage is ignored, the column stays empty, verify ignores it too, no event`() {
        val h = AccountHarness(props(enabled = false))
        h.signUpWith("ann@example.com", "not a valid id!!")
        h.signUpWith("bob@example.com", null)
        h.registration.verifyEmail(h.signUpIds.getValue("ann@example.com"), h.lastCode("ann@example.com"), null, loginId = "admin")
        assertNull(h.repo.findByEmail("ann@example.com")!!.loginId)
        assertTrue(h.events.all.none { it.type == AccountEventType.LOGIN_ID_SET })
        assertTrue(h.callLog.calls.none { it.second == "findByLoginId" || it.second == "loginIdUsed" }, "off: the login id tables are never read")
    }

    @Test
    fun `seed accounts get their id without the reserved check, with its hash`() {
        val seeded = props().copy(seed = AccountProperties.Seed(listOf(AccountProperties.SeedAccount(email = "root@example.com", password = "tangerine-42-moon", loginId = "Admin"))))
        val h = AccountHarness(seeded)
        AccountSeeder(h.core).seed()
        assertEquals("admin", h.repo.findByEmail("root@example.com")!!.loginId)
        assertTrue(h.repo.loginIdUsed(h.core.blocks.loginIdHash("admin")))
    }

    @Test
    fun `off - a seed account's login-id is ignored`() {
        val seeded = props(enabled = false).copy(seed = AccountProperties.Seed(listOf(AccountProperties.SeedAccount(email = "root@example.com", password = "tangerine-42-moon", loginId = "rootid"))))
        val h = AccountHarness(seeded)
        AccountSeeder(h.core).seed()
        assertNull(h.repo.findByEmail("root@example.com")!!.loginId)
    }

    private fun seedAccount(email: String, loginId: String?, id: String? = null) = AccountProperties.SeedAccount(id = id, email = email, password = "tangerine-42-moon", loginId = loginId)

    @Test
    fun `a malformed seed login-id stops the start and names the setting and its position, not the value (M11)`() {
        val bad = "bad id!!"
        val h = AccountHarness(props().copy(seed = AccountProperties.Seed(listOf(seedAccount("ok@example.com", "okayid"), seedAccount("root@example.com", bad)))))
        val e = assertFailsWith<IllegalStateException> { AccountSeeder(h.core).seed() }
        assertTrue("skeleton.account.seed.accounts[1].login-id" in e.message.orEmpty(), e.message)
        assertTrue("Pattern" in e.message.orEmpty(), e.message)
        assertTrue(bad !in e.message.orEmpty(), "the value stays out of the message")
    }

    @Test
    fun `a seed account skipped because its login id is taken logs one line with the account id and not the id (M11)`() {
        val logger = org.slf4j.LoggerFactory.getLogger(AccountSeeder::class.java) as ch.qos.logback.classic.Logger
        val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            val h = AccountHarness(props().copy(seed = AccountProperties.Seed(listOf(seedAccount("first@example.com", "same.seed"), seedAccount("second@example.com", "same.seed", id = "acc_second_seed")))))
            AccountSeeder(h.core).seed()
            assertNull(h.repo.findByEmail("second@example.com"))
            val skipped = appender.list.map { it.formattedMessage }.filter { "acc_second_seed" in it }
            assertEquals(1, skipped.size, appender.list.map { it.formattedMessage }.toString())
            assertTrue("same.seed" !in skipped.single() && "second@example.com" !in skipped.single(), skipped.single())
        } finally {
            logger.detachAppender(appender)
        }
    }
}
