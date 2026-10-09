package dev.sumin.skeleton.account

import dev.sumin.skeleton.common.ApplicationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * §9.6 — 버리기는 "이메일 없는 계정(아무것도 쓰지 못한 계정)"을 유예 없이 지운다. 조건은 `required-when` 과 무관하게 그대로이고,
 * 아이디를 정한 계정도 버릴 수 있으며 그 아이디는 다시 쓸 수 없다.
 */
class EmailOnWriteDiscardTest {
    private val ses = "ses_1"

    private fun harness(whenRequired: AccountProperties.Email.RequiredWhen = AccountProperties.Email.RequiredWhen.WRITE, loginId: Boolean = true) = AccountHarness(
        props = AccountProperties(
            mail = AccountProperties.Mail(linkBaseUrl = "https://app.example.com"),
            password = AccountProperties.Password(bcryptStrength = 4),
            email = AccountProperties.Email(required = true, requiredWhen = whenRequired),
            loginId = AccountProperties.LoginId(enabled = loginId),
        ),
    )

    private fun code(block: () -> Unit) = assertFailsWith<ApplicationException> { block() }.errorCode.code

    private fun AccountHarness.shell(id: String = "acc_k", loginId: String? = null): Account {
        val now = time.now()
        val account = Account(id, null, false, AccountStatus.ACTIVE, setOf("USER"), "K", null, null, now, now, lastLoginAt = now, loginId = loginId)
        repo.insert(account, listOf(Identity("idn_$id", id, "kakao", "kakao-$id", true, createdAt = now)), core.loginIdHashOf(loginId))
        sessions.open(id, ses)
        return account
    }

    @Test
    fun `in the write mode an account that chose an id can be discarded - and nobody can take that id again`() {
        val h = harness()
        val a = h.shell(loginId = "gone.soon")
        h.discard.discard(a.id, ReauthInput(), ses)

        assertEquals(AccountStatus.ERASED, h.repo.findById(a.id)!!.status)
        assertNull(h.repo.findById(a.id)!!.loginId, "the readable id is dropped with the account")
        assertNull(h.repo.findByLoginId("gone.soon"))
        assertFalse(h.core.loginIdAvailable("gone.soon"), "the id stays taken - the tombstone hash outlives the account")
        val newcomer = h.shell("acc_new")
        assertEquals("ACCOUNT.LOGIN_ID_TAKEN", code { h.loginIds.claim(newcomer.id, "Gone.Soon", "203.0.113.1") })
        assertNull(h.repo.findById(newcomer.id)!!.loginId)
    }

    @Test
    fun `the conditions are the same in both modes - an address, an inactive account, the setting off are all 409`() {
        for (mode in AccountProperties.Email.RequiredWhen.entries) {
            val h = harness(mode, loginId = false)
            val withAddress = h.activeAccount("ann-${mode.name}@example.com")
            h.sessions.open(withAddress.id, ses)
            assertEquals("ACCOUNT.DISCARD_NOT_ALLOWED", code { h.discard.discard(withAddress.id, ReauthInput("tangerine-42-moon"), ses) }, "$mode: has an address")

            val shell = h.shell()
            h.discard.discard(shell.id, ReauthInput(), ses)
            assertEquals(AccountStatus.ERASED, h.repo.findById(shell.id)!!.status, "$mode: the shell is discarded at once")
        }
        val off = AccountHarness(AccountProperties(mail = AccountProperties.Mail(linkBaseUrl = "https://app.example.com"), password = AccountProperties.Password(bcryptStrength = 4)))
        val now = off.time.now()
        off.repo.insert(Account("acc_o", null, false, AccountStatus.ACTIVE, setOf("USER"), "K", null, null, now, now, lastLoginAt = now), listOf(Identity("idn_o", "acc_o", "kakao", "kakao-o", true, createdAt = now)))
        assertEquals("ACCOUNT.DISCARD_NOT_ALLOWED", code { off.discard.discard("acc_o", ReauthInput(), ses) }, "without email.required there is no such account")
    }

    @Test
    fun `the first-registration window is the same rule in the write mode`() {
        for (mode in AccountProperties.Email.RequiredWhen.entries) {
            val h = harness(mode, loginId = false)
            val a = h.shell()
            assertEquals(true, h.core.freshLoginSession(h.repo.findById(a.id)!!, ses), "$mode: a session that just logged in is inside the window")
            h.time.advance(java.time.Duration.ofHours(2))
            assertEquals(false, h.core.freshLoginSession(h.repo.findById(a.id)!!, ses), "$mode: two hours later the window (1 hour) is over")
        }
    }
}
