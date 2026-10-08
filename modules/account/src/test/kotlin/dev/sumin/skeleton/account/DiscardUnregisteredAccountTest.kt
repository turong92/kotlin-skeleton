package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.events.AccountEventType
import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.erasure.ErasureRequest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 등록 미완료 계정(`email.required=true` 인데 주소를 한 번도 가진 적 없는 계정)을 **본인이 즉시 버린다** — 이메일로 가입해 둔 사람이 Kakao 로 들어와 생긴 껍데기 계정이
 * 소셜 수단을 쥔 채 막다른 길이 되지 않게(30일 유예 없이 수단이 풀려 기존 계정에서 연결할 수 있다).
 */
class DiscardUnregisteredAccountTest {
    private val ses = "ses_1"

    private fun harness(required: Boolean = true, mode: AccountProperties.Deletion.Mode = AccountProperties.Deletion.Mode.ANONYMIZE, social: SocialReauthVerifier? = null) = AccountHarness(
        props = AccountProperties(
            mail = AccountProperties.Mail(linkBaseUrl = "https://app.example.com"),
            password = AccountProperties.Password(bcryptStrength = 4),
            email = AccountProperties.Email(required = required),
            deletion = AccountProperties.Deletion(mode = mode),
        ),
        socialReauth = social,
    )

    private fun code(block: () -> Unit) = assertFailsWith<ApplicationException> { block() }.errorCode.code

    /** 방금 Kakao 로 로그인해 세션 [ses] 가 열린, 주소 없는 껍데기 계정 */
    private fun AccountHarness.shell(id: String = "acc_k", status: AccountStatus = AccountStatus.ACTIVE): Account {
        val now = time.now()
        val account = Account(id, null, false, status, setOf("USER"), "K", null, null, now, now, lastLoginAt = now)
        repo.insert(account, listOf(Identity("idn_$id", id, "kakao", "kakao-$id", true, createdAt = now)))
        sessions.open(id, ses)
        return account
    }

    @Test
    fun `a shell account that just signed in discards itself at once - the sign-in method is free, other modules erase, sessions close`() {
        val h = harness()
        val seen = mutableListOf<ErasureRequest>()
        h.erasers += object : AccountErasureListener { override val name = "board"; override fun erase(request: ErasureRequest) { seen += request } }
        val a = h.shell()

        h.discard.discard(a.id, ReauthInput(), ses)

        assertEquals(AccountStatus.ERASED, h.repo.findById(a.id)!!.status, "no 30-day grace: erased at once")
        assertNull(h.repo.findIdentity("kakao", "kakao-${a.id}"), "Kakao is free - the real account can link it now")
        assertEquals(listOf(a.id), seen.map { it.accountId }, "the other modules' erasure listeners ran")
        assertEquals(listOf<Pair<String, String?>>(a.id to null), h.revoker.calls, "every session of the shell is closed, also the calling one")
        assertTrue(AccountEventType.ACCOUNT_DISCARDED in h.events.types())
    }

    @Test
    fun `with deletion mode DELETE the row goes too`() {
        val h = harness(mode = AccountProperties.Deletion.Mode.DELETE)
        val a = h.shell()
        h.discard.discard(a.id, ReauthInput(), ses)
        assertNull(h.repo.findById(a.id))
        assertNull(h.repo.findIdentity("kakao", "kakao-${a.id}"))
    }

    @Test
    fun `the conditions are strict - an address, the setting off, a suspended or an already deleted account are all 409`() {
        val h = harness()
        val withAddress = h.activeAccount("ann@example.com")
        h.sessions.open(withAddress.id, ses)
        assertEquals("ACCOUNT.DISCARD_NOT_ALLOWED", code { h.discard.discard(withAddress.id, ReauthInput("tangerine-42-moon"), ses) }, "an account that has an address is deleted the normal way")

        val off = harness(required = false)
        val o = off.shell()
        assertEquals("ACCOUNT.DISCARD_NOT_ALLOWED", code { off.discard.discard(o.id, ReauthInput(), ses) }, "without email.required there is no shell account")

        val suspended = h.shell("acc_s", AccountStatus.SUSPENDED)
        assertEquals("ACCOUNT.DISCARD_NOT_ALLOWED", code { h.discard.discard(suspended.id, ReauthInput(), ses) }, "a suspension cannot be shaken off")
        assertEquals(AccountStatus.SUSPENDED, h.repo.findById(suspended.id)!!.status)

        val deleted = h.shell("acc_d")
        h.repo.update(deleted.id, AccountPatch(status = AccountStatus.DELETED, deletedAt = h.time.now(), purgeAfter = h.time.now().plus(Duration.ofDays(30))), h.time.now())
        assertEquals("ACCOUNT.DISCARD_NOT_ALLOWED", code { h.discard.discard(deleted.id, ReauthInput(), ses) }, "a deletion with a grace period is not a discard")
    }

    @Test
    fun `a session that did not just log in needs the provider re-authentication, the same as for the first address`() {
        val verifier = SocialReauthVerifier { _, proof -> proof.authorizationCode == "fresh" }
        val h = harness(social = verifier)
        val a = h.shell()
        h.time.advance(Duration.ofDays(2))   // the session is old now (a refresh token that keeps rotating)

        assertEquals("ACCOUNT.REAUTH_REQUIRED", code { h.discard.discard(a.id, ReauthInput(), ses) })
        assertEquals("ACCOUNT.REAUTH_FAILED", code { h.discard.discard(a.id, ReauthInput(social = SocialReauth("kakao", "stale")), ses) })
        assertEquals(AccountStatus.ACTIVE, h.repo.findById(a.id)!!.status, "nothing happened")

        h.discard.discard(a.id, ReauthInput(social = SocialReauth("kakao", "fresh")), ses)
        assertEquals(AccountStatus.ERASED, h.repo.findById(a.id)!!.status)
    }

    @Test
    fun `an old session of the shell cannot discard just because the owner logged in elsewhere`() {
        val h = harness()
        val a = h.shell()
        h.time.advance(Duration.ofDays(3))
        h.sessions.open(a.id, "ses_owner")
        assertEquals("ACCOUNT.REAUTH_REQUIRED", code { h.discard.discard(a.id, ReauthInput(), ses) })
        h.discard.discard(a.id, ReauthInput(), "ses_owner")
    }

    @Test
    fun `when another module's erasure fails the shell is left deleted and due - the repeated call or the purge job finishes it`() {
        val h = harness()
        var broken = true
        h.erasers += object : AccountErasureListener { override val name = "flaky"; override fun erase(request: ErasureRequest) { if (broken) error("db down") } }
        val a = h.shell()

        assertEquals("ACCOUNT.ERASURE_RETRY", code { h.discard.discard(a.id, ReauthInput(), ses) })
        assertEquals(AccountStatus.DELETED, h.repo.findById(a.id)!!.status, "signed out and unusable, the identity is still held until the retry")
        assertNotNull(h.repo.findIdentity("kakao", "kakao-${a.id}"))

        broken = false
        h.discard.discard(a.id, ReauthInput(), ses)   // the repeat completes it (idempotent)
        assertEquals(AccountStatus.ERASED, h.repo.findById(a.id)!!.status)
        assertNull(h.repo.findIdentity("kakao", "kakao-${a.id}"))
    }

    @Test
    fun `the purge job finishes a discard that failed halfway`() {
        val h = harness()
        var broken = true
        h.erasers += object : AccountErasureListener { override val name = "flaky"; override fun erase(request: ErasureRequest) { if (broken) error("db down") } }
        val a = h.shell()
        code { h.discard.discard(a.id, ReauthInput(), ses) }
        broken = false
        assertEquals(1, h.purge.purgeDue())
        assertEquals(AccountStatus.ERASED, h.repo.findById(a.id)!!.status)
    }

    @Test
    fun `the last administrator cannot discard itself`() {
        val h = harness()
        val a = h.shell()
        h.repo.grantRole(a.id, h.core.props.admin.role, h.time.now())
        assertEquals("ACCOUNT.LAST_ADMIN", code { h.discard.discard(a.id, ReauthInput(), ses) })
        assertEquals(AccountStatus.ACTIVE, h.repo.findById(a.id)!!.status)
    }
}
