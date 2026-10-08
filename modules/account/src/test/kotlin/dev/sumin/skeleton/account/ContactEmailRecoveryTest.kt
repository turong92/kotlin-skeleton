package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.events.AccountSessionEventListener
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.auth.session.SessionEvent
import dev.sumin.skeleton.auth.session.SessionEventType
import dev.sumin.skeleton.common.ApplicationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 연락 이메일은 로그인 수단이 아니지만 **서비스 알림이 가는 곳**이다 — 탈취된 세션이 공격자 주소를 넣어 두면, 주인이 계정을 되찾은 뒤에도 알림이 공격자에게 간다.
 * 그래서 주인의 복구 동작(메일함 증명 · 현재 비밀번호로 바꾸기 · 모든 기기 로그아웃)이 연락 이메일도 비운다. 주소 없는 계정의 등록 · 변경은 알림이 갈 곳이 없으므로 다시 인증한다.
 */
class ContactEmailRecoveryTest {
    private fun harness(social: SocialReauthVerifier? = null) = AccountHarness(
        props = AccountProperties(
            mail = AccountProperties.Mail(linkBaseUrl = "https://app.example.com"),
            password = AccountProperties.Password(bcryptStrength = 4),
            contactEmail = AccountProperties.ContactEmail(enabled = true),
        ),
        socialReauth = social,
    )

    private val ses = "ses_1"
    private fun code(block: () -> Unit) = assertFailsWith<ApplicationException> { block() }.errorCode.code

    /** 탈취된 세션이 공격자 주소를 연락 이메일로 심어 둔 계정 */
    private fun AccountHarness.plantedByThief(): Account {
        val a = activeAccount()
        contact.request(a.id, "thief@example.com", ses)
        contact.confirm(a.id, ses, mailer.of(MailKind.CONTACT_EMAIL_CODE).last().vars.getValue("code"))
        assertEquals("thief@example.com", repo.findById(a.id)!!.contactEmail)
        return a
    }

    @Test
    fun `the owner's password reset - a mailbox proof - clears the planted contact address`() {
        val h = harness()
        val a = h.plantedByThief()
        h.passwords.forgot("ann@example.com", "203.0.113.1", null)
        h.passwords.reset(h.mailer.tokenOf(h.mailer.of(MailKind.PASSWORD_RESET).last()), "a-brand-new-pass-7")
        assertNull(h.repo.findById(a.id)!!.contactEmail, "service messages no longer reach the thief")
        assertNull(h.profile.me(a.id).contactEmail?.orElse(null))
    }

    @Test
    fun `changing the password with the current password clears it too`() {
        val h = harness()
        val a = h.plantedByThief()
        h.passwords.change(a.id, "tangerine-42-moon", "a-brand-new-pass-7", "ses_owner")
        assertNull(h.repo.findById(a.id)!!.contactEmail)
    }

    @Test
    fun `a pending contact-email code of the thief dies with the mailbox proof`() {
        val h = harness()
        val a = h.activeAccount()
        h.contact.request(a.id, "thief@example.com", ses)
        val pending = h.mailer.of(MailKind.CONTACT_EMAIL_CODE).last().vars.getValue("code")
        h.passwords.forgot("ann@example.com", "203.0.113.1", null)
        h.passwords.reset(h.mailer.tokenOf(h.mailer.of(MailKind.PASSWORD_RESET).last()), "a-brand-new-pass-7")
        assertEquals("ACCOUNT.CODE_EXPIRED", code { h.contact.confirm(a.id, ses, pending) })
        assertNull(h.repo.findById(a.id)!!.contactEmail)
    }

    @Test
    fun `signing out everywhere clears it - but an internal session close does not`() {
        val h = harness()
        val a = h.plantedByThief()
        val listener = AccountSessionEventListener(h.publisher) { h.contact.clearAfterRecovery(it) }
        listener.on(SessionEvent(SessionEventType.SESSIONS_REVOKED, a.id, count = 2, reason = "REVOKED_ALL"))
        assertEquals("thief@example.com", h.repo.findById(a.id)!!.contactEmail, "internal revoke-all (email change, admin, ...) is not the owner's recovery")
        listener.on(SessionEvent(SessionEventType.SESSIONS_REVOKED, a.id, count = 2, reason = dev.sumin.skeleton.auth.session.SessionRevokeReasons.USER_REVOKED_ALL))
        assertNull(h.repo.findById(a.id)!!.contactEmail)
    }

    @Test
    fun `an account without an account address needs a fresh provider proof to register or change a contact address`() {
        val h = harness(social = SocialReauthVerifier { _, proof -> proof.authorizationCode == "fresh" })
        val now = h.time.now()
        val a = Account("acc_k", null, false, AccountStatus.ACTIVE, setOf("USER"), "K", null, null, now, now)
        h.repo.insert(a, listOf(Identity("idn_k", a.id, "kakao", "kakao-k", true, createdAt = now)))

        assertEquals("ACCOUNT.REAUTH_REQUIRED", code { h.contact.request(a.id, "c@example.com", ses) })
        assertEquals("ACCOUNT.REAUTH_FAILED", code { h.contact.request(a.id, "c@example.com", ses, ReauthInput(social = SocialReauth("kakao", "stale"))) })
        assertTrue(h.mailer.of(MailKind.CONTACT_EMAIL_CODE).isEmpty())

        h.contact.request(a.id, "c@example.com", ses, ReauthInput(social = SocialReauth("kakao", "fresh")))
        assertEquals("c@example.com", h.mailer.of(MailKind.CONTACT_EMAIL_CODE).single().to)
    }
}
