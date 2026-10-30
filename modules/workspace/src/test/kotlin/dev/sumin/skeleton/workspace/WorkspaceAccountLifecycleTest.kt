package dev.sumin.skeleton.workspace

import dev.sumin.skeleton.common.account.DeletionBlocker
import dev.sumin.skeleton.common.erasure.AccountTombstone
import dev.sumin.skeleton.common.erasure.ErasureRequest
import dev.sumin.skeleton.workspace.erasure.WorkspaceAccountErasureListener
import dev.sumin.skeleton.workspace.erasure.WorkspaceDeletionCheck
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 계정 삭제 — 요청 때 막기(다른 멤버가 있는 소유자)와, 그래도 지워질 때의 안전망(가장 오래된 ADMIN → MEMBER 에게 이전, 혼자면 닫음) */
class WorkspaceAccountLifecycleTest {
    private val store = FakeWorkspaceStore()
    private val clock = MutableClock()
    private val service = WorkspaceService(store, clock, WorkspaceSeatPolicy { null })
    private val check = WorkspaceDeletionCheck(store)
    private val erasure = WorkspaceAccountErasureListener(store, clock)

    private fun team(owner: String, name: String): String { clock.advance(1); return service.create(owner, name).workspace.id }
    private fun join(wsId: String, who: String, role: WorkspaceRole) { clock.advance(1); store.insertMember(WorkspaceMember(wsId, who, role, clock.now())) }
    private fun erase(accountId: String) = erasure.erase(ErasureRequest(accountId, AccountTombstone.of(accountId)))

    @Test
    fun `an owner of a workspace with other members is blocked, listing every such workspace`() {
        val a = team("acc_o", "One"); join(a, "acc_m", WorkspaceRole.MEMBER)
        val b = team("acc_o", "Two"); join(b, "acc_x", WorkspaceRole.ADMIN)
        team("acc_o", "Alone")
        assertEquals(listOf(DeletionBlocker("workspace", "OWNER", listOf(a, b))), check.blockers("acc_o"))
    }

    @Test
    fun `an owner who is alone, or a plain member or admin, is not blocked`() {
        team("acc_o", "Alone")
        val b = team("acc_p", "Other"); join(b, "acc_a", WorkspaceRole.ADMIN); join(b, "acc_m", WorkspaceRole.MEMBER)
        assertEquals(emptyList(), check.blockers("acc_o"))
        assertEquals(emptyList(), check.blockers("acc_a"))
        assertEquals(emptyList(), check.blockers("acc_m"))
    }

    @Test
    fun `a closed workspace does not block`() {
        val a = team("acc_o", "One"); join(a, "acc_m", WorkspaceRole.MEMBER)
        store.closeWorkspace(a, clock.now())
        assertEquals(emptyList(), check.blockers("acc_o"))
    }

    @Test
    fun `erasing an owner hands the workspace to the oldest admin, else the oldest member`() {
        val withAdmin = team("acc_o", "One")
        join(withAdmin, "acc_m1", WorkspaceRole.MEMBER); join(withAdmin, "acc_a2", WorkspaceRole.ADMIN); join(withAdmin, "acc_a3", WorkspaceRole.ADMIN)
        val onlyMembers = team("acc_o", "Two")
        join(onlyMembers, "acc_m5", WorkspaceRole.MEMBER); join(onlyMembers, "acc_m6", WorkspaceRole.MEMBER)

        erase("acc_o")

        assertEquals(WorkspaceRole.OWNER, store.findMember(withAdmin, "acc_a2")!!.role)
        assertEquals(WorkspaceRole.OWNER, store.findMember(onlyMembers, "acc_m5")!!.role)
        for (id in listOf(withAdmin, onlyMembers)) {
            assertNull(store.findMember(id, "acc_o"))
            assertEquals(1, store.listMembers(id).count { it.role == WorkspaceRole.OWNER })
            assertEquals(WorkspaceStatus.ACTIVE, store.findWorkspace(id)!!.status)
        }
    }

    @Test
    fun `erasing a lone owner closes the workspace, and erasing twice changes nothing more`() {
        val alone = team("acc_o", "Alone")
        erase("acc_o")
        assertEquals(WorkspaceStatus.CLOSED, store.findWorkspace(alone)!!.status)
        assertNull(store.findMember(alone, "acc_o"))
        val before = store.all() to store.membersIncludingAll()
        erase("acc_o")
        assertEquals(before, store.all() to store.membersIncludingAll())
    }

    @Test
    fun `erasing a non-owner only removes the membership and blanks created-by and invited-by`() {
        val id = team("acc_o", "One")
        join(id, "acc_m", WorkspaceRole.MEMBER)
        erase("acc_m")
        assertEquals(listOf("acc_o"), store.listMembers(id).map { it.accountId })
        erase("acc_o")
        assertEquals(AccountTombstone.of("acc_o"), store.findWorkspace(id)!!.createdBy)
    }
}
