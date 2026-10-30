package dev.sumin.skeleton.account

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.account.AccountDeletionCheck
import dev.sumin.skeleton.common.account.DeletionBlocker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class AccountDeletionBlockTest {
    private val blocking = AccountDeletionCheck { id -> listOf(DeletionBlocker("workspace", "OWNER", listOf("ws_1", "ws_2"))) }
    private val other = AccountDeletionCheck { listOf(DeletionBlocker("billing", "UNSETTLED")) }
    private val quiet = AccountDeletionCheck { emptyList() }

    @Test
    fun `blockers from every check stop the delete request with 409 and stay listed together`() {
        val h = AccountHarness(deletionChecks = listOf(blocking, quiet, other))
        val a = h.activeAccount()
        val e = assertFailsWith<ApplicationException> { h.deletion.delete(a.id, ReauthInput("tangerine-42-moon"), null) }
        assertEquals("ACCOUNT.DELETION_BLOCKED", e.errorCode.code)
        assertEquals(409, e.errorCode.status.value())
        assertEquals(
            mapOf("blockers" to listOf(
                mapOf("module" to "workspace", "code" to "OWNER", "refs" to listOf("ws_1", "ws_2")),
                mapOf("module" to "billing", "code" to "UNSETTLED", "refs" to emptyList<String>()),
            )),
            e.data,
        )
        assertEquals(AccountStatus.ACTIVE, h.repo.findById(a.id)!!.status, "a blocked account is not scheduled for deletion")
        assertEquals(emptyList(), h.revoker.calls, "no session is closed")
    }

    @Test
    fun `checks that report nothing, or no check at all, let the delete through`() {
        val h = AccountHarness(deletionChecks = listOf(quiet))
        val a = h.activeAccount()
        assertNotNull(h.deletion.delete(a.id, ReauthInput("tangerine-42-moon"), null))
        assertEquals(AccountStatus.DELETED, h.repo.findById(a.id)!!.status)
    }

    @Test
    fun `an already scheduled deletion stays idempotent even if a blocker appeared after it`() {
        var blocked = false
        val h = AccountHarness(deletionChecks = listOf(AccountDeletionCheck { if (blocked) listOf(DeletionBlocker("workspace", "OWNER")) else emptyList() }))
        val a = h.activeAccount()
        val first = h.deletion.delete(a.id, ReauthInput("tangerine-42-moon"), null)
        blocked = true
        assertEquals(first, h.deletion.delete(a.id, ReauthInput("tangerine-42-moon"), null))
    }
}
