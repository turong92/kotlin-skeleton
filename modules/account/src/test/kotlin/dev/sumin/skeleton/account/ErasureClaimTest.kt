package dev.sumin.skeleton.account

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.erasure.ErasureRequest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * I3 — 지우기는 리스너(board 톰스톤 · 받은편지함 삭제 …)를 부르기 **전에** 계정 행을 선점한다. 선점한 뒤에는 되살리기 · 정지 해제가 이길 수 없다 —
 * 유예 경계에서 시계가 어긋난(또는 시작이 빨랐던) 취소가 이겨서 계정은 ACTIVE 인데 이미 톰스톤이 찍힌 상태가 되지 않는다.
 */
class ErasureClaimTest {
    private val PW = ReauthInput("tangerine-42-moon")

    private fun harness() = AccountHarness(
        AccountProperties(mail = AccountProperties.Mail(linkBaseUrl = "https://app.example.com"), password = AccountProperties.Password(bcryptStrength = 4)),
    )

    private fun AccountHarness.leave(): Account {
        val a = activeAccount()
        deletion.delete(a.id, PW, null)
        time.advance(Duration.ofDays(30).plusSeconds(1))   // the grace has just ended
        return a
    }

    /** 리스너가 도는 한가운데서 "시계가 느린 인스턴스의 취소"(유예가 아직 안 끝난 시각을 본다) 가 도착한다 */
    private fun AccountHarness.skewedRestoreDuringErasure(id: String, outcome: MutableList<Boolean>) {
        erasers += object : AccountErasureListener {
            override val name = "probe"
            override fun erase(request: ErasureRequest) { outcome += repo.restore(id, AccountStatus.ACTIVE, time.now().minusSeconds(2)) }
        }
    }

    @Test
    fun `a cancel that reads a slower clock cannot win once the purge started - the account ends erased, never active with a tombstone`() {
        val h = harness()
        val a = h.leave()
        val outcome = mutableListOf<Boolean>()
        h.skewedRestoreDuringErasure(a.id, outcome)
        assertEquals(1, h.purge.purgeDue())
        assertEquals(listOf(false), outcome, "the claim taken before the listeners refuses the restore")
        assertEquals(AccountStatus.ERASED, h.repo.findById(a.id)!!.status)
    }

    @Test
    fun `a restore that gets in before the purge started still wins - and then no listener runs at all`() {
        val h = harness()
        val a = h.leave()
        assertTrue(h.repo.restore(a.id, AccountStatus.ACTIVE, h.time.now().minusSeconds(2)), "the slower clock still sees the grace as running")
        var calls = 0
        h.erasers += object : AccountErasureListener { override val name = "x"; override fun erase(request: ErasureRequest) { calls++ } }
        assertEquals(0, h.purge.purgeDue())
        assertEquals(0, calls)
        assertEquals(AccountStatus.ACTIVE, h.repo.findById(a.id)!!.status)
    }

    @Test
    fun `a failing listener leaves the claim - the retry erases, and the account cannot be restored in between`() {
        val h = harness()
        val a = h.leave()
        var broken = true
        h.erasers += object : AccountErasureListener { override val name = "flaky"; override fun erase(request: ErasureRequest) { if (broken) error("db down") } }
        assertEquals(0, h.purge.purgeDue())
        assertFalse(h.repo.restore(a.id, AccountStatus.ACTIVE, h.time.now().minusSeconds(2)), "claimed: the owner's grace is over, nothing brings it back")
        assertEquals(AccountStatus.DELETED, h.repo.findById(a.id)!!.status)
        broken = false
        assertEquals(1, h.purge.purgeDue())
        assertEquals(AccountStatus.ERASED, h.repo.findById(a.id)!!.status)
    }

    @Test
    fun `an unsuspend that arrives while an administrator's erase runs its listeners does not bring the account back`() {
        val h = harness()
        val admin = h.activeAccount("admin@example.com").also { h.repo.grantRole(it.id, "ADMIN", h.time.now()) }
        val a = h.activeAccount("ann@example.com")
        h.admin.suspend(admin.id, a.id, "fraud")
        var updated: Account? = a
        h.erasers += object : AccountErasureListener {
            override val name = "probe"
            override fun erase(request: ErasureRequest) { updated = h.repo.update(a.id, AccountPatch(status = AccountStatus.ACTIVE, clearSuspendedReason = true), h.time.now()) }
        }
        assertTrue(h.purge.eraseSuspended(admin.id, a.id, "fraud"))
        assertNull(updated, "the claim refuses a status change")
        assertEquals(AccountStatus.ERASED, h.repo.findById(a.id)!!.status)
    }

    @Test
    fun `an administrator's erase whose listener fails keeps the account suspended and the call can simply be repeated`() {
        val h = harness()
        val admin = h.activeAccount("admin@example.com").also { h.repo.grantRole(it.id, "ADMIN", h.time.now()) }
        val a = h.activeAccount("ann@example.com")
        h.admin.suspend(admin.id, a.id, "fraud")
        var broken = true
        h.erasers += object : AccountErasureListener { override val name = "flaky"; override fun erase(request: ErasureRequest) { if (broken) error("db down") } }
        val e = assertFailsWith<ApplicationException> { h.purge.eraseSuspended(admin.id, a.id, "fraud") }
        assertEquals("ACCOUNT.ERASURE_RETRY", e.errorCode.code, "a retryable 503, not an internal error")
        assertEquals(AccountStatus.SUSPENDED, h.repo.findById(a.id)!!.status)
        assertEquals(0, h.admin.listBlocks(0, 10).total, "no block is recorded for an erase that did not happen")
        broken = false
        assertTrue(h.purge.eraseSuspended(admin.id, a.id, "fraud"))
        assertEquals(AccountStatus.ERASED, h.repo.findById(a.id)!!.status)
    }
}
