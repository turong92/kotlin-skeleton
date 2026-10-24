package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.events.AccountEventType
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.common.ApplicationException
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 연락받을 이메일 — 계정 이메일과 별개의 선택 주소 하나. 로그인 · 복구 수단이 아니고 유니크하지 않다. 어디로 무엇이 가나: docs/accounts.md "연락 이메일" 표, 한 곳에 모은 시험은 [MailRoutingTest] */
class ContactEmailServiceTest {
    private val h = AccountHarness(
        props = AccountProperties(
            mail = AccountProperties.Mail(linkBaseUrl = "https://app.example.com"),
            password = AccountProperties.Password(bcryptStrength = 4),
            contactEmail = AccountProperties.ContactEmail(enabled = true),
        ),
    )
    private val ses = "ses_1"
    private fun code(block: () -> Unit) = assertFailsWith<ApplicationException> { block() }.errorCode.code
    private fun sentCode() = h.mailer.of(MailKind.CONTACT_EMAIL_CODE).last().vars.getValue("code")

    @Test
    fun `the code goes to the contact address only, nothing is stored before it is entered, the account address is not mailed yet`() {
        val a = h.activeAccount()
        h.mailer.sent.clear()

        val window = h.contact.request(a.id, "Contact@Example.com", ses)

        assertEquals("contact@example.com", h.mailer.of(MailKind.CONTACT_EMAIL_CODE).single().to)
        assertEquals(1, h.mailer.sent.size)
        assertEquals(h.time.now().plus(Duration.ofMinutes(10)), window.expiresAt)
        assertNull(h.repo.findById(a.id)!!.contactEmail)
    }

    @Test
    fun `entering the code in the same session stores it, tells the account address once, and keeps the account address and sessions`() {
        val a = h.activeAccount()
        h.contact.request(a.id, "contact@example.com", ses)
        val code = sentCode()
        h.mailer.sent.clear()

        h.contact.confirm(a.id, ses, code)

        val after = h.repo.findById(a.id)!!
        assertEquals("contact@example.com", after.contactEmail)
        assertEquals("ann@example.com", after.email, "the account address is untouched")
        assertEquals("ann@example.com", h.mailer.of(MailKind.CONTACT_EMAIL_NOTICE).single().to)
        assertEquals(1, h.mailer.sent.size)
        assertTrue(h.revoker.calls.isEmpty(), "a contact address is not a credential - no session is closed")
        assertEquals("contact@example.com", h.profile.me(a.id).contactEmail?.orElse(null))
    }

    @Test
    fun `wrong code counts down, other session and replayed code are expired`() {
        val a = h.activeAccount()
        h.contact.request(a.id, "contact@example.com", ses)
        val good = sentCode()
        val wrong = if (good == "000000") "000001" else "000000"
        assertEquals("ACCOUNT.CODE_INVALID", code { h.contact.confirm(a.id, ses, wrong) })
        assertEquals("ACCOUNT.CODE_EXPIRED", code { h.contact.confirm(a.id, "ses_other", good) })
        h.contact.confirm(a.id, ses, good)
        assertEquals("ACCOUNT.CODE_EXPIRED", code { h.contact.confirm(a.id, ses, good) })
    }

    @Test
    fun `an address that is another account's email or contact is allowed - there is no uniqueness and nothing to reveal`() {
        val owner = h.activeAccount("owner@example.com")
        val a = h.activeAccount("ann@example.com")
        h.contact.request(a.id, "owner@example.com", ses)
        assertEquals("owner@example.com", h.mailer.of(MailKind.CONTACT_EMAIL_CODE).single().to, "a code goes out like for any address")
        h.contact.confirm(a.id, ses, sentCode())
        assertEquals("owner@example.com", h.repo.findById(a.id)!!.contactEmail)

        val b = h.activeAccount("bob@example.com")
        h.contact.request(b.id, "owner@example.com", "ses_b")
        h.contact.confirm(b.id, "ses_b", sentCode())
        assertEquals("owner@example.com", h.repo.findById(b.id)!!.contactEmail, "two accounts may share a contact address")
        assertEquals(owner.email, h.repo.findById(owner.id)!!.email)
    }

    @Test
    fun `an address the operator erased can be a contact address - it is not a registration`() {
        val admin = h.activeAccount("admin@example.com").also { h.repo.grantRole(it.id, "ADMIN", h.time.now()) }
        val gone = h.activeAccount("gone@example.com")
        h.admin.suspend(admin.id, gone.id, "fraud", setOf("*"))
        assertTrue(h.purge.eraseSuspended(admin.id, gone.id, "fraud", setOf("*")))
        val a = h.activeAccount()
        h.contact.request(a.id, "gone@example.com", ses)
        h.contact.confirm(a.id, ses, sentCode())
        assertEquals("gone@example.com", h.repo.findById(a.id)!!.contactEmail)
    }

    @Test
    fun `changing replaces it and removing is immediate - both tell the account address`() {
        val a = h.activeAccount()
        h.contact.request(a.id, "one@example.com", ses); h.contact.confirm(a.id, ses, sentCode())
        h.contact.request(a.id, "two@example.com", ses); h.contact.confirm(a.id, ses, sentCode())
        assertEquals("two@example.com", h.repo.findById(a.id)!!.contactEmail)

        h.mailer.sent.clear()
        h.contact.remove(a.id)
        assertNull(h.repo.findById(a.id)!!.contactEmail)
        assertEquals(listOf("ann@example.com"), h.mailer.of(MailKind.CONTACT_EMAIL_NOTICE).map { it.to })
        h.contact.remove(a.id)   // already none: a quiet no-op
        assertEquals(1, h.mailer.sent.size)
        assertEquals(3, h.events.types().count { it == AccountEventType.CONTACT_EMAIL_CHANGED })
    }

    @Test
    fun `an implausible address and a flood are refused like the email change`() {
        val a = h.activeAccount()
        assertEquals("COMMON.VALIDATION_FAILED", code { h.contact.request(a.id, "not-an-address", ses) })
        repeat(4) { h.contact.request(a.id, "x$it@example.com", ses) }
        assertEquals("ACCOUNT.RATE_LIMITED", code { h.contact.request(a.id, "x9@example.com", ses) })
    }

    @Test
    fun `erasing the account clears the contact address`() {
        val a = h.activeAccount()
        h.contact.request(a.id, "contact@example.com", ses); h.contact.confirm(a.id, ses, sentCode())
        h.repo.update(a.id, AccountPatch(status = AccountStatus.SUSPENDED), h.time.now())
        assertTrue(h.repo.erase(a.id, h.time.now(), forced = true))
        assertNull(h.repo.findById(a.id)!!.contactEmail)
    }
}
