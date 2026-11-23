package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.abuse.RateLimitedException
import dev.sumin.skeleton.account.mail.MailKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 아이디 찾기와 비밀번호 찾기는 한도 통(IP · 주소)을 따로 쓴다 */
class LoginIdReminderBucketsTest {
    private fun props() = AccountProperties(
        mail = AccountProperties.Mail(linkBaseUrl = "https://app.example.com"), password = AccountProperties.Password(bcryptStrength = 4),
        loginId = AccountProperties.LoginId(enabled = true),
    )

    @Test
    fun `its buckets are its own - password reset requests do not spend the reminder allowance, nor the other way round`() {
        val h = AccountHarness(props())
        h.activeAccount("ann@example.com", displayName = null, loginId = "ann.kim", locale = "ko")
        repeat(10) { h.passwords.forgot("ann@example.com", "198.51.100.7", null) }
        assertEquals(3, h.mailer.of(MailKind.PASSWORD_RESET).size)
        h.reminder.forgot("ann@example.com", "198.51.100.7", null)   // the IP is spent for password/forgot, not for this
        assertEquals(1, h.mailer.of(MailKind.LOGIN_ID_REMINDER).size, "the address was spent for the reset mail, not for this one")
        repeat(9) { h.reminder.forgot("z$it@example.com", "198.51.100.7", null) }
        assertFailsWith<RateLimitedException> { h.reminder.forgot("z@example.com", "198.51.100.7", null) }
        h.passwords.forgot("nobody@example.com", "198.51.100.8", null)   // the other IP is untouched by the reminder buckets
    }
}
