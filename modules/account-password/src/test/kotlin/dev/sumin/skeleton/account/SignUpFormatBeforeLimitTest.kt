package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.abuse.AccountCaptcha
import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.FieldValidationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Q2 — a request that is refused for its FORM alone (a nickname the rules refuse, an address that cannot be one) costs nothing: it creates no attempt, sends no mail and asks no outside service,
 * so it must not use up the per-IP sign-up allowance (a person behind a shared address who mistypes a few times would lock the others out).
 * What the allowance protects stays behind it: the consent lookup, the captcha call, the password policy (it may call a breach service), the hash, the attempt and the mail.
 */
class SignUpFormatBeforeLimitTest {
    private fun harness(captcha: AccountCaptcha? = null, nicknameRequired: Boolean = false) = AccountHarness(
        AccountProperties(
            mail = AccountProperties.Mail(linkBaseUrl = "https://app.example.com"), password = AccountProperties.Password(bcryptStrength = 4),
            signUp = AccountProperties.SignUp(perIp = 2), displayName = AccountProperties.DisplayName(requiredOnSignUp = nicknameRequired),
        ),
        captcha = captcha,
    )

    @Test
    fun `refused nicknames and impossible addresses never use the IP allowance - the same IP can still sign up twice afterwards`() {
        val h = harness()
        repeat(6) { assertEquals("Pattern", assertFailsWith<FieldValidationException> { h.signUp("a$it@example.com", displayName = "bad#name") }.fieldCode) }
        repeat(6) { assertEquals("COMMON.VALIDATION_FAILED", assertFailsWith<ApplicationException> { h.signUp("no-dot-$it@localhost") }.errorCode.code) }
        h.signUp("one@example.com"); h.signUp("two@example.com")
        assertEquals("ACCOUNT.RATE_LIMITED", assertFailsWith<ApplicationException> { h.signUp("three@example.com") }.errorCode.code, "the allowance is 2 and only real requests used it")
    }

    @Test
    fun `a missing required nickname is a form error too - it costs no allowance`() {
        val h = harness(nicknameRequired = true)
        repeat(5) { assertEquals("Required", assertFailsWith<FieldValidationException> { h.signUp("a$it@example.com", displayName = null) }.fieldCode) }
        h.signUp("one@example.com"); h.signUp("two@example.com")
    }

    @Test
    fun `what the allowance protects stays behind it - a request over the allowance never reaches the captcha service, a form error never does either`() {
        var calls = 0
        val h = harness(captcha = AccountCaptcha { _, _, _ -> calls++; true })
        assertFailsWith<FieldValidationException> { h.signUp("bad@example.com", displayName = "bad#name") }
        assertEquals(0, calls, "a form error is answered before the captcha service is asked")
        h.signUp("one@example.com", captchaToken = "t"); h.signUp("two@example.com", captchaToken = "t")
        assertEquals(2, calls)
        assertFailsWith<ApplicationException> { h.signUp("three@example.com", captchaToken = "t") }
        assertEquals(2, calls, "over the allowance: still no external call")
        assertTrue(h.mailer.sent.size <= 2)
    }

    @Test
    fun `a refused login id is a form error too - it costs no allowance and never reaches the captcha service`() {
        var calls = 0
        val h = AccountHarness(
            AccountProperties(
                mail = AccountProperties.Mail(linkBaseUrl = "https://app.example.com"), password = AccountProperties.Password(bcryptStrength = 4),
                signUp = AccountProperties.SignUp(perIp = 2), loginId = AccountProperties.LoginId(enabled = true),
            ),
            captcha = AccountCaptcha { _, _, _ -> calls++; true },
        )
        fun attempt(email: String, loginId: String?) = h.registration.signUp(SignUpCommand(email, "tangerine-42-moon", null, null, null, "203.0.113.1", "t", loginId = loginId))
        repeat(5) { assertEquals("Pattern", assertFailsWith<FieldValidationException> { attempt("a$it@example.com", "bad id") }.fieldCode) }
        assertEquals("Required", assertFailsWith<FieldValidationException> { attempt("a@example.com", null) }.fieldCode)
        assertEquals(0, calls)
        attempt("one@example.com", "oneid"); attempt("two@example.com", "twoid")
        assertEquals("ACCOUNT.RATE_LIMITED", assertFailsWith<ApplicationException> { attempt("three@example.com", "threeid") }.errorCode.code)
        assertEquals(2, calls)
    }
}
