package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.mail.MailKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "어디로 무엇이 가나" (docs/accounts.md "연락 이메일 — 어디로 무엇이 가나" 표)를 **받는 주소별 통수**로 못 박는다:
 * 계정 · 보안 메일은 늘 계정 이메일 하나 — 연락 이메일로는 한 통도 가지 않고, 연락 이메일로 계정을 찾을 수도 없다. 비밀번호 찾기 같은 것을 여러 주소에 동시에 뿌리지 않는다.
 */
class MailRoutingTest {
    private val contact = "contact@example.com"
    private val account = "ann@example.com"

    private val h = AccountHarness(
        props = AccountProperties(
            mail = AccountProperties.Mail(linkBaseUrl = "https://app.example.com"),
            password = AccountProperties.Password(bcryptStrength = 4),
            contactEmail = AccountProperties.ContactEmail(enabled = true),
        ),
    )
    private val ses = "ses_1"
    private fun to(address: String) = h.mailer.sent.filter { it.to == address }
    private fun sentTo() = h.mailer.sent.groupingBy { it.to }.eachCount()

    private fun withContact(): Account {
        val a = h.activeAccount(account)
        h.contact.request(a.id, contact, ses)
        h.contact.confirm(a.id, ses, h.mailer.of(MailKind.CONTACT_EMAIL_CODE).last().vars.getValue("code"))
        h.mailer.sent.clear()
        return a
    }

    @Test
    fun `password reset goes to the account address only - once - and the contact address gets nothing`() {
        withContact()
        h.passwords.forgot(account, "203.0.113.1", null)
        assertEquals(mapOf(account to 1), sentTo())
        assertEquals(MailKind.PASSWORD_RESET, to(account).single().kind)
        assertEquals(0, to(contact).size)
    }

    @Test
    fun `asking for a reset with the contact address sends nothing at all - it does not find the account`() {
        withContact()
        h.passwords.forgot(contact, "203.0.113.1", null)   // same call, same (empty) result as for any unknown address
        h.passwords.forgot("nobody@example.com", "203.0.113.2", null)
        assertTrue(h.mailer.sent.isEmpty(), "sent: ${sentTo()}")
    }

    @Test
    fun `signing up with the account address mails the account address, never the contact one`() {
        withContact()
        h.signUp(account, ip = "203.0.113.3")
        assertEquals(mapOf(account to 1), sentTo())
        assertEquals(MailKind.ALREADY_REGISTERED, to(account).single().kind)
    }

    @Test
    fun `a contact address is not an occupied address - signing up with it is the plain new-address path`() {
        withContact()
        h.signUp(contact, ip = "203.0.113.4")
        assertEquals(mapOf(contact to 1), sentTo())
        assertEquals(MailKind.VERIFY_CODE, to(contact).single().kind, "it is a free address: a sign-up code, no 'already registered' and nothing about ann's account")
    }

    @Test
    fun `an email change code goes to the new account-address candidate only, the old account address is told, the contact address is not`() {
        val a = withContact()
        h.emailChange.request(a.id, "new@example.com", ReauthInput("tangerine-42-moon"), ses)
        assertEquals(mapOf("new@example.com" to 1, account to 1), sentTo())
        assertEquals(MailKind.EMAIL_CHANGE_CODE, to("new@example.com").single().kind)
        assertEquals(MailKind.EMAIL_CHANGE_REQUESTED_NOTICE, to(account).single().kind)
        assertEquals(0, to(contact).size)
    }

    @Test
    fun `re-authentication and deletion codes and the deletion notice go to the account address only`() {
        val a = withContact()
        h.reauth.requestConfirmation(a.id, ses)
        h.deletion.requestConfirmation(a.id, ses)
        assertEquals(mapOf(account to 2), sentTo())
        assertEquals(setOf(MailKind.REAUTH_CODE, MailKind.DELETE_CODE), to(account).map { it.kind }.toSet())

        h.mailer.sent.clear()
        h.deletion.delete(a.id, ReauthInput("tangerine-42-moon"), ses)
        assertEquals(setOf(account), sentTo().keys, "deletion notice: ${sentTo()}")
        assertEquals(0, to(contact).size)
    }

    @Test
    fun `registering, changing and removing the contact address tell the account address - the code goes to the contact address`() {
        val a = h.activeAccount(account)
        h.mailer.sent.clear()
        h.contact.request(a.id, contact, ses)
        assertEquals(mapOf(contact to 1), sentTo())
        h.contact.confirm(a.id, ses, h.mailer.of(MailKind.CONTACT_EMAIL_CODE).single().vars.getValue("code"))
        assertEquals(MailKind.CONTACT_EMAIL_NOTICE, to(account).single().kind)
        h.contact.remove(a.id)
        assertEquals(2, to(account).size)
        assertEquals(1, to(contact).size, "only the code, never a notice about itself")
    }

    @Test
    fun `the service contact address is the contact address when there is one, else the account address`() {
        val a = h.activeAccount(account)
        val addresses = AccountContactAddresses(h.core)
        assertEquals(account, addresses.contactEmail(a.id))
        h.contact.request(a.id, contact, ses)
        h.contact.confirm(a.id, ses, h.mailer.of(MailKind.CONTACT_EMAIL_CODE).single().vars.getValue("code"))
        assertEquals(contact, addresses.contactEmail(a.id))
        assertEquals(null, addresses.contactEmail("acc_nobody"))
    }
}
