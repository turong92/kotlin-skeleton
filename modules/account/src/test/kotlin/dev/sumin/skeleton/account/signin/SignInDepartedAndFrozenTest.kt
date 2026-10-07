package dev.sumin.skeleton.account.signin

import dev.sumin.skeleton.account.AccountHarness
import dev.sumin.skeleton.account.AccountProperties
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.account.events.AccountEventType
import dev.sumin.skeleton.auth.account.LoginBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * I1 — 비밀번호로 가입한 계정이 탈퇴 유예 중에 매직 링크를 열면 "탈퇴 대기" 상태가 나온다 (수단이 없어도) ·
 * I7 — 정지는 박제: 정지된 계정에는 로그인 경로가 아무것도 붙이지도 기록하지도 않고, 그냥 정지 상태를 돌려준다
 */
class SignInDepartedAndFrozenTest {
    private val PW = dev.sumin.skeleton.account.ReauthInput("tangerine-42-moon")
    private object Google : SignInMethod { override val code = "google"; override val userRemovable = true }
    private object Magic : SignInMethod {
        override val code = "magic_link"
        override fun normalize(subject: String) = subject.trim().lowercase()
        override val provesEmail = true
        override val exposesSubject = true
    }

    private fun harness(selfRestore: Boolean = true, merge: Boolean = false) = AccountHarness(
        AccountProperties(
            deletion = AccountProperties.Deletion(selfRestore = selfRestore), social = AccountProperties.Social(signUp = true, mergeOnVerifiedEmail = merge),
            mail = AccountProperties.Mail(linkBaseUrl = "https://x"), password = AccountProperties.Password(bcryptStrength = 4),
        ),
    )

    private fun AccountHarness.service() = AccountSignInService(core, SignInMethodRegistry(listOf(PasswordSignInMethod(), Google, Magic)))
    private fun magic(email: String = "ann@example.com") = SignInProof("magic_link", email, email, true, null, null, "203.0.113.1", false)
    private fun google(subject: String, email: String?) = SignInProof("google", subject, email, email != null, "Ann", "ko", "203.0.113.1", true)

    @Test
    fun `a password-only account in its deletion grace gets the pending state from a magic link, and no magic_link method is attached`() {
        val h = harness()
        val a = h.activeAccount()
        h.deletion.delete(a.id, PW, null)
        val auth = assertNotNull(h.service().signIn(magic()))
        assertEquals(a.id, auth.accountId)
        assertEquals(LoginBlock.DELETION_PENDING, auth.loginBlock)
        assertEquals(setOf("password"), h.repo.identitiesOf(a.id).map { it.method }.toSet(), "a departed account gets no new sign-in method")
        assertEquals(AccountStatus.DELETED, h.repo.findById(a.id)!!.status)
    }

    @Test
    fun `with self-restore off a deleted account still looks like no account to a magic link`() {
        val h = harness(selfRestore = false)
        val a = h.activeAccount()
        h.deletion.delete(a.id, PW, null)
        assertNull(h.service().signIn(magic()))
        assertEquals(setOf("password"), h.repo.identitiesOf(a.id).map { it.method }.toSet())
    }

    @Test
    fun `a magic link cannot sign into an account whose grace has already ended, restorable or not`() {
        val h = harness()
        val a = h.activeAccount()
        h.deletion.delete(a.id, PW, null)
        h.time.advance(java.time.Duration.ofDays(31))
        assertNull(h.service().signIn(magic()))
    }

    @Test
    fun `a magic link into a suspended account attaches nothing and records no login - it answers suspended`() {
        val h = harness()
        val admin = h.activeAccount("admin@example.com").also { h.repo.grantRole(it.id, "ADMIN", h.time.now()) }
        val a = h.activeAccount()
        h.admin.suspend(admin.id, a.id, "abuse")
        val eventsBefore = h.events.all.size
        val auth = assertNotNull(h.service().signIn(magic()))
        assertEquals(LoginBlock.SUSPENDED, auth.loginBlock)
        assertEquals(setOf("password"), h.repo.identitiesOf(a.id).map { it.method }.toSet(), "no magic_link identity was added to a frozen account")
        assertNull(h.repo.findById(a.id)!!.lastLoginAt)
        assertEquals(emptyList(), h.events.all.drop(eventsBefore).filter { it.type == AccountEventType.LOGIN_SUCCESS || it.type == AccountEventType.IDENTITY_LINKED })
    }

    @Test
    fun `an existing identity of a suspended account is not touched either - no last-used stamp, no login record`() {
        val h = harness()
        val admin = h.activeAccount("admin@example.com").also { h.repo.grantRole(it.id, "ADMIN", h.time.now()) }
        val a = h.activeAccount()
        h.repo.addIdentity(dev.sumin.skeleton.account.Identity(h.core.newIdentityId(), a.id, "google", "g-ann", true, createdAt = h.time.now()))
        h.admin.suspend(admin.id, a.id, "abuse")
        h.time.advance(java.time.Duration.ofMinutes(5))
        val auth = assertNotNull(h.service().signIn(google("g-ann", null)))
        assertEquals(LoginBlock.SUSPENDED, auth.loginBlock)
        assertNull(h.repo.findIdentity("google", "g-ann")!!.lastUsedAt)
        assertNull(h.repo.findById(a.id)!!.lastLoginAt)
        assertTrue(h.events.all.none { it.type == AccountEventType.LOGIN_SUCCESS && it.accountId == a.id })
    }

    @Test
    fun `a merging social sign-in into a suspended account links nothing`() {
        val h = harness(merge = true)
        val admin = h.activeAccount("admin@example.com").also { h.repo.grantRole(it.id, "ADMIN", h.time.now()) }
        val a = h.activeAccount()
        h.admin.suspend(admin.id, a.id, "abuse")
        val auth = assertNotNull(h.service().signIn(google("g-new", "ann@example.com")))
        assertEquals(LoginBlock.SUSPENDED, auth.loginBlock)
        assertNull(h.repo.findIdentity("google", "g-new"))
        assertEquals(0, h.mailer.of(dev.sumin.skeleton.account.mail.MailKind.IDENTITY_LINKED_NOTICE).size)
    }
}
