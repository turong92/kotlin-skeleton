package dev.sumin.skeleton.workspace

import dev.sumin.skeleton.common.account.AccountLabels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceServiceTest {
    private val store = FakeWorkspaceStore()
    private val clock = MutableClock()
    private var seatLimit: Int? = null
    private val service = WorkspaceService(
        store, clock, WorkspaceSeatPolicy { seatLimit },
        labels = AccountLabels { ids -> ids.filter { it != "acc_nolabel" }.associateWith { "$it@example.com" } },
    )

    private fun code(block: () -> Unit) = assertFailsWith<WorkspaceException> { block() }.workspaceError

    /** 소유자 [owner] 의 워크스페이스에 [role] 로 [who] 를 들인다 (초대 흐름은 K3 — 여기서는 줄만 넣는다) */
    private fun join(wsId: String, who: String, role: WorkspaceRole) {
        clock.advance(1)
        store.insertMember(WorkspaceMember(wsId, who, role, clock.now()))
    }

    private fun team(owner: String = "acc_o", name: String = "Example Team"): String {
        clock.advance(1)
        return service.create(owner, name).workspace.id
    }

    // ---- 만들기 · slug

    @Test
    fun `creating makes the caller the single owner`() {
        val mine = service.create("acc_o", "  Example Team  ")
        assertEquals("Example Team", mine.workspace.name)
        assertEquals(WorkspaceRole.OWNER, mine.role)
        assertEquals(listOf(WorkspaceRole.OWNER), store.listMembers(mine.workspace.id).map { it.role })
        assertEquals(mine.workspace.id, service.list("acc_o").single().workspace.id)
        assertTrue(mine.workspace.id.startsWith("ws_"))
    }

    @Test
    fun `slug comes from the name, gets a counter when taken, and falls back to team for names without latin letters`() {
        assertEquals("example-team", service.create("acc_a", "Example Team").workspace.slug)
        assertEquals("example-team-2", service.create("acc_b", "Example  Team!").workspace.slug)
        assertEquals("example-team-3", service.create("acc_c", "example team").workspace.slug)
        assertEquals("cafe", service.create("acc_d", "Café").workspace.slug)
        assertEquals("team", service.create("acc_e", "우리 팀").workspace.slug)
        assertEquals("team-2", service.create("acc_f", "チーム").workspace.slug)
        assertEquals("a".repeat(40), service.create("acc_g", "a".repeat(80)).workspace.slug)
    }

    @Test
    fun `an explicit slug is used, a taken or invalid one is refused`() {
        assertEquals("my-team", service.create("acc_a", "Whatever", "my-team").workspace.slug)
        assertEquals(WorkspaceErrorCode.SLUG_TAKEN, code { service.create("acc_b", "Other", "my-team") })
        for (bad in listOf("ab", "-abc", "abc-", "ABC", "a b c", "a".repeat(41), "한글한글")) {
            assertEquals(WorkspaceErrorCode.INVALID, code { service.create("acc_c", "Other", bad) }, bad)
        }
    }

    @Test
    fun `a blank or oversized name is refused`() {
        assertEquals(WorkspaceErrorCode.INVALID, code { service.create("acc_a", "   ") })
        assertEquals(WorkspaceErrorCode.INVALID, code { service.create("acc_a", "x".repeat(101)) })
    }

    // ---- 읽기

    @Test
    fun `detail counts members and live invitations and shows the seat limit from the policy`() {
        val id = team()
        join(id, "acc_m", WorkspaceRole.MEMBER)
        store.liveInvitations = 3
        seatLimit = 10
        val d = service.detail("acc_m", id)
        assertEquals(WorkspaceDetail(d.workspace, WorkspaceRole.MEMBER, 2, 3, 10), d)
        seatLimit = null
        assertNull(service.detail("acc_o", id).seatLimit)
    }

    @Test
    fun `members list carries labels only for accounts that have one, owner first by join time`() {
        val id = team()
        join(id, "acc_nolabel", WorkspaceRole.MEMBER)
        val rows = service.members("acc_o", id)
        assertEquals(listOf("acc_o", "acc_nolabel"), rows.map { it.member.accountId })
        assertEquals(listOf("acc_o@example.com", null), rows.map { it.label })
    }

    @Test
    fun `an unknown id, a workspace I am not in, and a closed workspace answer the very same error`() {
        val id = team()
        val closed = team("acc_c", "Closed One")
        store.closeWorkspace(closed, clock.now())
        val answers = listOf(
            assertFailsWith<WorkspaceException> { service.detail("acc_x", id) },
            assertFailsWith<WorkspaceException> { service.detail("acc_x", "ws_nope") },
            assertFailsWith<WorkspaceException> { service.detail("acc_c", closed) },
        )
        assertEquals(1, answers.map { it.workspaceError to it.message }.toSet().size)
        assertEquals(WorkspaceErrorCode.NOT_FOUND, answers.first().workspaceError)
        assertEquals(WorkspaceErrorCode.NOT_FOUND, code { service.members("acc_x", id) })
        assertEquals(WorkspaceErrorCode.NOT_FOUND, code { service.leave("acc_x", id) })
        assertEquals(emptyList(), service.list("acc_c"), "a closed workspace is not in my list")
    }

    // ---- 이름 · slug 바꾸기

    @Test
    fun `admins and the owner rename, plain members cannot`() {
        val id = team()
        join(id, "acc_a", WorkspaceRole.ADMIN)
        join(id, "acc_m", WorkspaceRole.MEMBER)
        assertEquals("Renamed", service.update("acc_a", id, name = "Renamed", slug = null).workspace.name)
        assertEquals(WorkspaceErrorCode.FORBIDDEN, code { service.update("acc_m", id, "Nope", null) })
        assertEquals(WorkspaceErrorCode.INVALID, code { service.update("acc_o", id, null, null) })
    }

    @Test
    fun `changing the slug keeps the old one reserved, resolvable and out of other teams' reach`() {
        val id = team()
        val other = team("acc_p", "Other Team")
        val oldSlug = service.detail("acc_o", id).workspace.slug
        service.update("acc_o", id, null, "new-name")

        assertEquals(WorkspaceErrorCode.SLUG_TAKEN, code { service.update("acc_p", other, null, oldSlug) }, "a retired slug cannot be hijacked")
        assertEquals(WorkspaceErrorCode.SLUG_TAKEN, code { service.create("acc_q", "Third", oldSlug) })
        assertEquals(WorkspaceErrorCode.SLUG_TAKEN, code { service.update("acc_p", other, null, "new-name") }, "a current slug neither")

        assertEquals("new-name", service.bySlug("acc_o", oldSlug).workspace.slug, "the old address resolves to the current slug")
        assertEquals(id, service.bySlug("acc_o", "new-name").workspace.id)
        assertEquals(WorkspaceErrorCode.NOT_FOUND, code { service.bySlug("acc_p", oldSlug) }, "not a member -> the same 404")
        assertEquals(WorkspaceErrorCode.NOT_FOUND, code { service.bySlug("acc_o", "never-was") })
        assertEquals(id, service.bySlug("acc_o", "  NEW-Name ").workspace.id, "case does not matter, so PostgreSQL and MySQL answer alike")
        assertEquals(WorkspaceErrorCode.NOT_FOUND, code { service.bySlug("acc_o", "not a slug!") }, "a malformed address is a plain 404")

        assertEquals(oldSlug, service.update("acc_o", id, null, oldSlug).workspace.slug, "the same team may go back to its old slug")
        assertEquals(oldSlug, service.bySlug("acc_o", oldSlug).workspace.slug)
        assertEquals(WorkspaceErrorCode.SLUG_TAKEN, code { service.update("acc_p", other, null, "new-name") }, "the slug it left is retired now")
    }

    // ---- 멤버 관리

    @Test
    fun `only the owner changes roles, between admin and member`() {
        val id = team()
        join(id, "acc_a", WorkspaceRole.ADMIN)
        join(id, "acc_m", WorkspaceRole.MEMBER)
        service.setRole("acc_o", id, "acc_m", WorkspaceRole.ADMIN)
        assertEquals(WorkspaceRole.ADMIN, store.findMember(id, "acc_m")!!.role)
        service.setRole("acc_o", id, "acc_a", WorkspaceRole.MEMBER)
        assertEquals(WorkspaceErrorCode.FORBIDDEN, code { service.setRole("acc_m", id, "acc_a", WorkspaceRole.ADMIN) })
        assertEquals(WorkspaceErrorCode.INVALID, code { service.setRole("acc_o", id, "acc_a", WorkspaceRole.OWNER) })
        assertEquals(WorkspaceErrorCode.OWNER_PROTECTED, code { service.setRole("acc_o", id, "acc_o", WorkspaceRole.ADMIN) })
        assertEquals(WorkspaceErrorCode.MEMBER_NOT_FOUND, code { service.setRole("acc_o", id, "acc_ghost", WorkspaceRole.ADMIN) })
    }

    @Test
    fun `the owner removes anyone but themself, an admin removes members only`() {
        val id = team()
        join(id, "acc_a", WorkspaceRole.ADMIN)
        join(id, "acc_b", WorkspaceRole.ADMIN)
        join(id, "acc_m", WorkspaceRole.MEMBER)
        join(id, "acc_n", WorkspaceRole.MEMBER)
        assertEquals(WorkspaceErrorCode.FORBIDDEN, code { service.removeMember("acc_a", id, "acc_b") })
        assertEquals(WorkspaceErrorCode.FORBIDDEN, code { service.removeMember("acc_m", id, "acc_n") })
        assertEquals(WorkspaceErrorCode.FORBIDDEN, code { service.removeMember("acc_a", id, "acc_o") })
        service.removeMember("acc_a", id, "acc_m")
        service.removeMember("acc_o", id, "acc_b")
        assertEquals(WorkspaceErrorCode.OWNER_PROTECTED, code { service.removeMember("acc_o", id, "acc_o") })
        assertEquals(WorkspaceErrorCode.MEMBER_NOT_FOUND, code { service.removeMember("acc_o", id, "acc_m") })
        assertEquals(listOf("acc_o", "acc_a", "acc_n"), store.listMembers(id).map { it.accountId })
    }

    @Test
    fun `a removed member is out at once - detail, list and membership all say no`() {
        val id = team()
        join(id, "acc_m", WorkspaceRole.MEMBER)
        assertEquals(id, service.detail("acc_m", id).workspace.id)
        service.removeMember("acc_o", id, "acc_m")
        assertEquals(WorkspaceErrorCode.NOT_FOUND, code { service.detail("acc_m", id) })
        assertEquals(emptyList(), service.list("acc_m"))
        assertNull(service.membershipOf("acc_m", id))
    }

    @Test
    fun `members and admins can leave, the owner cannot`() {
        val id = team()
        join(id, "acc_a", WorkspaceRole.ADMIN)
        join(id, "acc_m", WorkspaceRole.MEMBER)
        service.leave("acc_m", id)
        service.leave("acc_a", id)
        assertEquals(WorkspaceErrorCode.OWNER_CANNOT_LEAVE, code { service.leave("acc_o", id) })
        assertEquals(listOf("acc_o"), store.listMembers(id).map { it.accountId })
    }

    // ---- 소유권 이전

    @Test
    fun `transfer swaps owner and admin in one step and leaves exactly one owner`() {
        val id = team()
        join(id, "acc_m", WorkspaceRole.MEMBER)
        service.transfer("acc_o", id, "acc_m")
        assertEquals(WorkspaceRole.ADMIN, store.findMember(id, "acc_o")!!.role)
        assertEquals(WorkspaceRole.OWNER, store.findMember(id, "acc_m")!!.role)
        assertEquals(1, store.listMembers(id).count { it.role == WorkspaceRole.OWNER })
        assertEquals(WorkspaceErrorCode.FORBIDDEN, code { service.transfer("acc_o", id, "acc_m") }, "the old owner no longer owns")
    }

    @Test
    fun `transfer needs the owner and an existing other member`() {
        val id = team()
        join(id, "acc_a", WorkspaceRole.ADMIN)
        assertEquals(WorkspaceErrorCode.FORBIDDEN, code { service.transfer("acc_a", id, "acc_a") })
        assertEquals(WorkspaceErrorCode.MEMBER_NOT_FOUND, code { service.transfer("acc_o", id, "acc_ghost") })
        assertEquals(WorkspaceErrorCode.INVALID, code { service.transfer("acc_o", id, "acc_o") })
        assertEquals(WorkspaceRole.OWNER, store.findMember(id, "acc_o")!!.role)
    }

    // ---- 앱 입구

    @Test
    fun `membershipOf and membersOf are the app-facing reads and ignore closed workspaces`() {
        val id = team()
        join(id, "acc_m", WorkspaceRole.MEMBER)
        assertEquals(WorkspaceRole.MEMBER, service.membershipOf("acc_m", id)!!.role)
        assertEquals(listOf("acc_o", "acc_m"), service.membersOf(id).map { it.accountId })
        store.closeWorkspace(id, clock.now())
        assertNull(service.membershipOf("acc_m", id))
        assertEquals(emptyList(), service.membersOf(id))
        assertNotEquals(null, store.findMember(id, "acc_m"))
    }
}
