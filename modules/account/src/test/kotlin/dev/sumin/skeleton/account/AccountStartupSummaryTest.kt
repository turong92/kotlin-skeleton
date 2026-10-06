package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.mail.AccountMailTransport
import dev.sumin.skeleton.account.mail.LogOnlyMailTransport
import dev.sumin.skeleton.account.web.SocialMethodView
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class AccountStartupSummaryTest {
    private val smtp = AccountMailTransport { _, _, _, _ -> true }

    @Test
    fun `lists the active sign-in methods, the social providers and the mail path without any secret`() {
        val line = AccountStartupSummary.describe(
            methods = listOf("password", "magic_link"),
            social = listOf(SocialMethodView("google", "1234.apps.googleusercontent.com", "http://localhost:5173/auth/callback"), SocialMethodView("kakao", null, null)),
            transport = smtp, smtpHost = "smtp.resend.com:587", from = "Notes <no-reply@notes.example>",
            mail = AccountProperties.Mail(linkBaseUrl = "http://localhost:5173"),
        )
        assertContains(line, "sign-in methods: password, magic_link")
        assertContains(line, "social: google(clientId=set, redirectUri=http://localhost:5173/auth/callback), kakao(clientId=MISSING, redirectUri=none)")
        assertContains(line, "mail: SMTP smtp.resend.com:587 from=Notes <no-reply@notes.example>")
        assertContains(line, "html=on")
        assertContains(line, "link-base-url=http://localhost:5173")
        assertFalse("1234.apps" in line, "the client id itself is not logged, only whether it is set")
    }

    @Test
    fun `says plainly when mails are not sent`() {
        val line = AccountStartupSummary.describe(listOf("password"), emptyList(), LogOnlyMailTransport(false), null, null, AccountProperties.Mail(htmlEnabled = false))
        assertContains(line, "social: none")
        assertContains(line, "mail: NOT SENT (log only")
        assertContains(line, "html=off")
    }
}
