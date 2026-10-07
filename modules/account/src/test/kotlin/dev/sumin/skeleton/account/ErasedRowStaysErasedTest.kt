package dev.sumin.skeleton.account

import dev.sumin.skeleton.common.ApplicationException
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** C1 — 지워진(ERASED) 행에는 아무것도 다시 쓰이지 않는다: 프로필 갱신 · 정지 해제 · 이메일 변경 · 로그인 수단 연결이 모두 저장소 조건에서 막힌다 */
class ErasedRowStaysErasedTest {
    private val PW = ReauthInput("tangerine-42-moon")

    private fun harness() = AccountHarness(
        AccountProperties(mail = AccountProperties.Mail(linkBaseUrl = "https://app.example.com"), password = AccountProperties.Password(bcryptStrength = 4)),
    )

    private fun AccountHarness.erased(): Account {
        val a = activeAccount()
        deletion.delete(a.id, PW, null)
        time.advance(Duration.ofDays(31))
        assertEquals(1, purge.purgeDue())
        return a
    }

    @Test
    fun `a profile update with a token that outlived the erasure is refused and writes nothing`() {
        val h = harness()
        val a = h.erased()
        val e = assertFailsWith<ApplicationException> { h.profile.update(a.id, ProfileChange("Mallory", "en", "UTC")) }
        assertEquals("ACCOUNT.NOT_FOUND", e.errorCode.code)
        val row = h.repo.findById(a.id)!!
        assertNull(row.displayName); assertNull(row.locale); assertNull(row.timeZone)
    }

    @Test
    fun `the repository itself refuses to update an erased row, whoever asks`() {
        val h = harness()
        val a = h.erased()
        assertNull(h.repo.update(a.id, AccountPatch(locale = "en", timeZone = "UTC", status = AccountStatus.ACTIVE), h.time.now()))
        assertEquals(SetNameResult.NOT_FOUND, h.repo.setDisplayName(a.id, "Mallory", "mallory", "0001", h.time.now()), "a name cannot be written onto an erased row either")
        assertEquals(GuardedResult.NOT_FOUND, h.repo.updateUnlessLast(a.id, AccountPatch(status = AccountStatus.ACTIVE), h.time.now(), "ADMIN"))
        val row = h.repo.findById(a.id)!!
        assertEquals(AccountStatus.ERASED, row.status)
        assertNull(row.displayName); assertNull(row.displayNameKey); assertNull(row.displayTag); assertNull(row.locale); assertNull(row.timeZone)
    }

    @Test
    fun `an unsuspend that lost the race against the erasure does not bring the account back`() {
        val h = harness()
        val admin = h.activeAccount("admin@example.com").also { h.repo.grantRole(it.id, "ADMIN", h.time.now()) }
        val a = h.activeAccount("ann@example.com")
        h.admin.suspend(admin.id, a.id, "abuse")
        val seen = h.admin.get(a.id)                                  // the unsuspend read this ...
        assertTrue(h.purge.eraseSuspended(admin.id, a.id, null))      // ... then the erasure won ...
        h.repo.update(seen.id, AccountPatch(status = AccountStatus.ACTIVE, clearSuspendedReason = true), h.time.now())   // ... and its write arrives late
        assertEquals(AccountStatus.ERASED, h.repo.findById(a.id)!!.status)
    }

    @Test
    fun `an erased row cannot get a new email or a sign-in method`() {
        val h = harness()
        val a = h.erased()
        assertEquals(ChangeEmailResult.NOT_FOUND, h.repo.changeEmail(a.id, "mallory@example.com", h.time.now()))
        assertFalse(h.repo.addIdentity(Identity(h.core.newIdentityId(), a.id, "google", "g-1", true, createdAt = h.time.now())))
        assertEquals(AddIdentityResult.STALE, h.repo.addIdentityIfEmailVerified(Identity(h.core.newIdentityId(), a.id, "google", "g-2", true, createdAt = h.time.now()), expectEmailVerified = false))
        assertNull(h.repo.findById(a.id)!!.email)
        assertTrue(h.repo.identitiesOf(a.id).isEmpty())
    }
}
