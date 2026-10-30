package dev.sumin.skeleton.workspace

import dev.sumin.skeleton.common.account.AccountLabels
import dev.sumin.skeleton.common.id.SortableIds
import dev.sumin.skeleton.common.time.TimeProvider

/**
 * 워크스페이스 만들기 · 읽기 · 이름 · slug · 멤버 관리 · 탈퇴 · 소유권 이전, 그리고 앱 입구([Workspaces]).
 *
 * **경계**: 없는 id · 멤버가 아님 · 닫힘은 모두 같은 `NOT_FOUND` 다(존재를 알리지 않는다). **변경은 모두 [WorkspaceStore.lockWorkspace] 로 줄을 잡은 뒤
 * 호출자의 멤버십을 다시 읽어 판정한다** — 동시에 온 소유권 이전 · 역할 변경 · 추방이 겹쳐도 소유자는 늘 하나이고, 먼저 진 쪽은 새 역할로 다시 판정된다.
 * 멤버십은 캐시하지 않는다 — 추방 · 역할 변경은 다음 호출부터 바로 반영된다.
 */
class WorkspaceService(
    private val store: WorkspaceStore,
    private val time: TimeProvider,
    private val seats: WorkspaceSeatPolicy,
    private val labels: AccountLabels? = null,
    private val ids: SortableIds = SortableIds.system,
) : Workspaces {
    // 만들기

    override fun create(accountId: String, name: String, slug: String?): MyWorkspace {
        val cleanName = cleanName(name)
        val explicit = slug?.trim()?.takeIf { it.isNotEmpty() }?.also { requireValidSlug(it) }
        val now = time.now()
        val id = ids.newId("ws_")
        return store.inTransaction {
            val workspace = if (explicit != null) {
                insert(id, cleanName, explicit, accountId, now) ?: throw WorkspaceException(WorkspaceErrorCode.SLUG_TAKEN)
            } else {
                val base = WorkspaceSlugs.fromName(cleanName)
                (1..MAX_AUTO_ATTEMPTS).firstNotNullOfOrNull { insert(id, cleanName, WorkspaceSlugs.candidate(base, it), accountId, now) }
                    ?: throw WorkspaceException(WorkspaceErrorCode.SLUG_TAKEN)
            }
            store.insertMember(WorkspaceMember(id, accountId, WorkspaceRole.OWNER, now))
            MyWorkspace(workspace, WorkspaceRole.OWNER)
        }
    }

    private fun insert(id: String, name: String, slug: String, createdBy: String, now: java.time.Instant): Workspace? {
        if (slugTaken(slug)) return null
        val workspace = Workspace(id, name, slug, WorkspaceStatus.ACTIVE, createdBy, now, now)
        return workspace.takeIf { store.insertWorkspace(it) }
    }

    private fun slugTaken(slug: String) = store.findWorkspaceBySlug(slug) != null || store.slugRetiredTo(slug) != null

    // 읽기

    fun list(accountId: String): List<MyWorkspace> = store.workspacesOf(accountId)

    fun detail(accountId: String, workspaceId: String): WorkspaceDetail {
        val (workspace, me) = readable(accountId, workspaceId)
        return detailOf(workspace, me)
    }

    /** 지금 slug 든 옛 slug 든 푼다 — 응답의 `slug` 는 늘 지금 값이다. 없음 · 멤버 아님 · 닫힘은 같은 `NOT_FOUND` */
    fun bySlug(accountId: String, slug: String): WorkspaceDetail {
        val workspace = store.findWorkspaceBySlug(slug) ?: store.slugRetiredTo(slug)?.let(store::findWorkspace) ?: throw notFound()
        val (found, me) = readable(accountId, workspace.id)
        return detailOf(found, me)
    }

    fun members(accountId: String, workspaceId: String): List<MemberView> {
        readable(accountId, workspaceId)
        val rows = store.listMembers(workspaceId)
        val names = labels?.of(rows.map { it.accountId }).orEmpty()
        return rows.map { MemberView(it, names[it.accountId]) }
    }

    private fun readable(accountId: String, workspaceId: String): Pair<Workspace, WorkspaceMember> {
        val me = store.activeMembership(workspaceId, accountId) ?: throw notFound()
        return (store.findWorkspace(workspaceId) ?: throw notFound()) to me
    }

    private fun detailOf(workspace: Workspace, me: WorkspaceMember) = WorkspaceDetail(
        workspace, me.role, store.countMembers(workspace.id), store.countLiveInvitations(workspace.id, time.now()), seats.limitOf(workspace.id),
    )

    override fun membershipOf(accountId: String, workspaceId: String): WorkspaceMember? = store.activeMembership(workspaceId, accountId)

    override fun membersOf(workspaceId: String): List<WorkspaceMember> =
        if (store.findWorkspace(workspaceId)?.status == WorkspaceStatus.ACTIVE) store.listMembers(workspaceId) else emptyList()

    // 바꾸기 — ADMIN 이상

    fun update(accountId: String, workspaceId: String, name: String?, slug: String?): WorkspaceDetail = mutate(accountId, workspaceId, WorkspaceRole.ADMIN) { workspace, me ->
        if (name == null && slug == null) throw invalid("name or slug is required")
        val nextName = name?.let(::cleanName) ?: workspace.name
        val nextSlug = slug?.trim()?.takeIf { it != workspace.slug }?.also(::requireValidSlug) ?: workspace.slug
        val now = time.now()
        if (nextSlug != workspace.slug) {
            val retiredTo = store.slugRetiredTo(nextSlug)
            if (retiredTo == workspace.id) store.releaseSlug(nextSlug)
            else if (retiredTo != null || store.findWorkspaceBySlug(nextSlug) != null) throw WorkspaceException(WorkspaceErrorCode.SLUG_TAKEN)
            store.retireSlug(workspace.slug, workspace.id, now)
        }
        if (!store.updateWorkspace(workspace.id, nextName, nextSlug, now)) throw WorkspaceException(WorkspaceErrorCode.SLUG_TAKEN)
        detailOf(store.findWorkspace(workspace.id) ?: throw notFound(), me)
    }

    // 멤버 — 역할은 OWNER, 추방은 ADMIN 이상(ADMIN 은 MEMBER 만)

    fun setRole(accountId: String, workspaceId: String, targetId: String, role: WorkspaceRole) = mutate(accountId, workspaceId, WorkspaceRole.OWNER) { _, _ ->
        if (role == WorkspaceRole.OWNER) throw invalid("role is ADMIN or MEMBER; use transfer to change the owner")
        val target = store.findMember(workspaceId, targetId) ?: throw WorkspaceException(WorkspaceErrorCode.MEMBER_NOT_FOUND)
        if (target.role == WorkspaceRole.OWNER) throw WorkspaceException(WorkspaceErrorCode.OWNER_PROTECTED)
        store.setRole(workspaceId, targetId, role)
        Unit
    }

    fun removeMember(accountId: String, workspaceId: String, targetId: String) = mutate(accountId, workspaceId, WorkspaceRole.ADMIN) { _, me ->
        val target = store.findMember(workspaceId, targetId) ?: throw WorkspaceException(WorkspaceErrorCode.MEMBER_NOT_FOUND)
        if (target.role == WorkspaceRole.OWNER) throw WorkspaceException(if (me.role == WorkspaceRole.OWNER) WorkspaceErrorCode.OWNER_PROTECTED else WorkspaceErrorCode.FORBIDDEN)
        if (me.role == WorkspaceRole.ADMIN && target.role != WorkspaceRole.MEMBER) throw WorkspaceException(WorkspaceErrorCode.FORBIDDEN)
        store.deleteMember(workspaceId, targetId)
        Unit
    }

    fun leave(accountId: String, workspaceId: String) = mutate(accountId, workspaceId, WorkspaceRole.MEMBER) { _, me ->
        if (me.role == WorkspaceRole.OWNER) throw WorkspaceException(WorkspaceErrorCode.OWNER_CANNOT_LEAVE)
        store.deleteMember(workspaceId, accountId)
        Unit
    }

    /** 소유권 이전 — 한 트랜잭션에서 옛 소유자는 ADMIN, 새 소유자는 OWNER. 옛 소유자를 먼저 내려야 소유자 표시(DB 유니크)가 겹치지 않는다 */
    fun transfer(accountId: String, workspaceId: String, targetId: String) = mutate(accountId, workspaceId, WorkspaceRole.OWNER) { _, _ ->
        if (targetId == accountId) throw invalid("the new owner must be another member")
        store.findMember(workspaceId, targetId) ?: throw WorkspaceException(WorkspaceErrorCode.MEMBER_NOT_FOUND)
        store.setRole(workspaceId, accountId, WorkspaceRole.ADMIN)
        store.setRole(workspaceId, targetId, WorkspaceRole.OWNER)
        Unit
    }

    /** 줄을 잠그고 → 닫히지 않았는지 → 호출자의 지금 멤버십 → 역할 검사 → [block]. 어느 단계든 실패하면 아무것도 바뀌지 않는다 */
    private fun <T> mutate(accountId: String, workspaceId: String, min: WorkspaceRole, block: (Workspace, WorkspaceMember) -> T): T = store.inTransaction {
        val workspace = store.lockWorkspace(workspaceId)?.takeIf { it.status == WorkspaceStatus.ACTIVE } ?: throw notFound()
        val me = store.findMember(workspaceId, accountId) ?: throw notFound()
        if (!me.role.atLeast(min)) throw WorkspaceException(WorkspaceErrorCode.FORBIDDEN)
        block(workspace, me)
    }

    private fun cleanName(raw: String): String =
        raw.trim().takeIf { it.isNotEmpty() && it.length <= MAX_NAME && it.none(Char::isISOControl) } ?: throw invalid("name is required (1-$MAX_NAME characters)")

    private fun requireValidSlug(slug: String) {
        if (!WorkspaceSlugs.VALID.matches(slug)) throw invalid("slug is 3-40 characters of a-z, 0-9 and hyphens, not starting or ending with a hyphen")
    }

    private fun invalid(message: String) = WorkspaceException(WorkspaceErrorCode.INVALID, message)

    private fun notFound() = WorkspaceException(WorkspaceErrorCode.NOT_FOUND)

    private companion object {
        const val MAX_NAME = 100
        const val MAX_AUTO_ATTEMPTS = 50
    }
}
