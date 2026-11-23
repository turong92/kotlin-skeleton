package dev.sumin.skeleton.account

import dev.sumin.skeleton.common.ApplicationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 정지는 박제다 — 비밀번호 쪽 길(가입 코드 · 첫 비밀번호)로도 정지된 계정을 이어받거나 수단을 더하지 못한다 */
class FrozenAccountPasswordTest {
    private fun code(block: () -> Unit) = assertFailsWith<ApplicationException> { block() }.errorCode.code
    private fun harness() = AccountHarness(
        AccountProperties(mail = AccountProperties.Mail(linkBaseUrl = "https://app.example.com"), password = AccountProperties.Password(bcryptStrength = 4)),
    )
    private fun AccountHarness.admin(): Account = repo.findByEmail("admin@example.com") ?: activeAccount("admin@example.com").also { repo.grantRole(it.id, "ADMIN", time.now()) }

    @Test
    fun `a sign-up code for the address of a suspended unproven account does not take the account over`() {
        val h = harness()
        h.admin()
        val now = h.time.now()
        h.repo.insert(Account("acc_u", "unproven@example.com", false, AccountStatus.SUSPENDED, setOf("USER"), null, null, null, now, now), emptyList())
        h.signUp("unproven@example.com")   // the same 202 as always
        assertEquals("ACCOUNT.REGISTRATION_BLOCKED", code { h.verify("unproven@example.com") })
        assertEquals(AccountStatus.SUSPENDED, h.repo.findById("acc_u")!!.status)
        assertTrue(h.repo.identitiesOf("acc_u").isEmpty())
    }

    @Test
    fun `after an administrator erased a suspended account the sign-up request still looks like any other, only the code is refused`() {
        val h = harness()
        val admin = h.admin()
        val a = h.activeAccount("ann@example.com")
        h.admin.suspend(admin.id, a.id, "abuse", setOf("*"))
        assertTrue(h.purge.eraseSuspended(admin.id, a.id, "fraud", setOf("*")))

        assertEquals(SignUpStatus.VERIFICATION_SENT, h.signUp("ann@example.com").status)
        assertEquals("ACCOUNT.REGISTRATION_BLOCKED", code { h.verify("ann@example.com") })
        assertNull(h.repo.findByEmail("ann@example.com"))
    }

    @Test
    fun `a suspended account without a password cannot set its first one - that would add a sign-in method`() {
        val h = harness()
        val admin = h.admin()
        val now = h.time.now()
        h.repo.insert(Account("acc_np", "np@example.com", true, AccountStatus.ACTIVE, setOf("USER"), null, null, null, now, now), listOf(Identity(h.core.newIdentityId(), "acc_np", "google", "g-np", true, createdAt = now)))
        h.admin.suspend(admin.id, "acc_np", "abuse", setOf("*"))
        h.reauth.requestConfirmation("acc_np", "ses_1")
        val mailed = h.mailer.of(dev.sumin.skeleton.account.mail.MailKind.REAUTH_CODE).single().vars.getValue("code")
        assertEquals("ACCOUNT.NOT_ACTIVE", code { h.passwords.change("acc_np", null, "tangerine-42-moon", "ses_1", mailed) })
        assertNull(h.repo.findIdentity("password", "np@example.com"))
    }
}
