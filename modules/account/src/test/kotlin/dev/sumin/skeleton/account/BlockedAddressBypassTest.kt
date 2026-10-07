package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.events.AccountEventType
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.account.signin.IdentityService
import dev.sumin.skeleton.account.signin.PasswordSignInMethod
import dev.sumin.skeleton.account.signin.SignInMethod
import dev.sumin.skeleton.account.signin.SignInMethodRegistry
import dev.sumin.skeleton.common.ApplicationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** I2 — 운영자가 지운 계정의 이메일 · 제공자 주체는 가입뿐 아니라 **이메일 변경 · 로그인 수단 연결**로도 다시 쓰이지 못한다 (증명을 마친 사람에게만 드러나는 거절) */
class BlockedAddressBypassTest {
    private val PW = ReauthInput("tangerine-42-moon")
    private object Google : SignInMethod { override val code = "google"; override val userRemovable = true }

    private fun harness() = AccountHarness(
        AccountProperties(mail = AccountProperties.Mail(linkBaseUrl = "https://app.example.com"), password = AccountProperties.Password(bcryptStrength = 4)),
    )

    private fun code(block: () -> Unit) = assertFailsWith<ApplicationException> { block() }.errorCode.code

    /** ann@example.com + 구글 g-ann 이 정지 뒤 운영자에게 지워져 차단된다 */
    private fun AccountHarness.blockedAnn() {
        val admin = activeAccount("admin@example.com").also { repo.grantRole(it.id, "ADMIN", time.now()) }
        val ann = activeAccount("ann@example.com")
        repo.addIdentity(Identity(core.newIdentityId(), ann.id, "google", "g-ann", true, createdAt = time.now()))
        this.admin.suspend(admin.id, ann.id, "fraud")
        assertTrue(purge.eraseSuspended(admin.id, ann.id, "fraud"))
    }

    @Test
    fun `an email change to a blocked address is refused at the confirmation, after the mailbox was proven, and changes nothing`() {
        val h = harness()
        h.blockedAnn()
        val bob = h.activeAccount("bob@example.com")
        h.emailChange.request(bob.id, "ann@example.com", PW, "ses_1")   // the request looks like any other: same mail, same answer
        val sent = h.mailer.of(MailKind.EMAIL_CHANGE_CODE).last()
        assertEquals("ann@example.com", sent.to)

        assertEquals("ACCOUNT.REGISTRATION_BLOCKED", code { h.emailChange.confirm(bob.id, "ses_1", sent.vars.getValue("code")) })
        assertEquals("bob@example.com", h.repo.findById(bob.id)!!.email)
        assertNull(h.repo.findByEmail("ann@example.com"))
        assertTrue(AccountEventType.REGISTRATION_BLOCKED in h.events.types())
    }

    @Test
    fun `linking a blocked provider account or an unblocked one`() {
        val h = harness()
        h.blockedAnn()
        val bob = h.activeAccount("bob@example.com")
        val identities = IdentityService(h.core, SignInMethodRegistry(listOf(PasswordSignInMethod(), Google)))
        assertEquals("ACCOUNT.REGISTRATION_BLOCKED", code { identities.link(bob.id, "google", "g-ann", verified = true) })
        assertNull(h.repo.findIdentity("google", "g-ann"))
        assertNotNull(identities.link(bob.id, "google", "g-bob", verified = true), "an unblocked provider account links as before")
    }

    @Test
    fun `an administrator lifting the block lets the address be used again`() {
        val h = harness()
        h.blockedAnn()
        val bob = h.activeAccount("bob@example.com")
        h.admin.listBlocks(0, 20).items.forEach { h.admin.removeBlock("acc_admin", it.id) }
        h.emailChange.request(bob.id, "ann@example.com", PW, "ses_1")
        h.emailChange.confirm(bob.id, "ses_1", h.mailer.of(MailKind.EMAIL_CHANGE_CODE).last().vars.getValue("code"))
        assertEquals("ann@example.com", h.repo.findById(bob.id)!!.email)
    }
}
