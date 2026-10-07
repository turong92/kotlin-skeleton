package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.mail.MailKind
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

/** B4 — 6자리 인증번호는 어디서 받든 유효 시간이 같다 (기본 10분). 링크 토큰인 비밀번호 재설정(30분)만 다르다 */
class CodeLifetimeTest {
    private val h = AccountHarness()

    @Test
    fun `every six-digit code defaults to the sign-up code lifetime, the reset link keeps its own`() {
        val p = AccountProperties()
        assertEquals(Duration.ofMinutes(10), p.verification.codeTtl)
        assertEquals(p.verification.codeTtl, p.emailChange.ttl, "email change and re-authentication codes")
        assertEquals(p.verification.codeTtl, p.deletion.confirmationTtl, "deletion confirmation code")
        assertEquals(Duration.ofMinutes(30), p.reset.ttl, "a link token, not a six-digit code")
    }

    @Test
    fun `the four code mails say ten minutes, and the stored codes really expire then`() {
        val a = h.activeAccount()
        h.emailChange.request(a.id, "new@example.com", ReauthInput("tangerine-42-moon"), "ses_1")
        h.reauth.requestConfirmation(a.id, "ses_1")
        h.deletion.requestConfirmation(a.id, "ses_1")
        assertEquals("10", h.mailer.of(MailKind.VERIFY_CODE).last().vars["minutes"])
        assertEquals("10", h.mailer.of(MailKind.EMAIL_CHANGE_CODE).last().vars["minutes"])
        assertEquals("10", h.mailer.of(MailKind.REAUTH_CODE).last().vars["minutes"])
        assertEquals("10", h.mailer.of(MailKind.DELETE_CODE).last().vars["minutes"])
        listOf("email_change", "reauth", "delete_confirm").forEach { purpose ->
            val row = h.challengeStore.findOpen(purpose, a.id, h.time.now())!!
            assertEquals(h.time.now().plus(Duration.ofMinutes(10)), row.expiresAt, purpose)
        }
    }
}
