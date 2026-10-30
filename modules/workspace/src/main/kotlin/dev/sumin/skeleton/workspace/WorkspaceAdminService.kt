package dev.sumin.skeleton.workspace

import dev.sumin.skeleton.common.time.TimeProvider

/**
 * 운영자 읽기 - 워크스페이스 목록 · 검색 · 상세(멤버 · 대기 초대 수). 읽기만 한다. 멤버십 경계(`CurrentWorkspace`)와 다른 축이라
 * 닫힌 워크스페이스도 보인다 - 접근 판정은 호출하는 쪽(운영자 컨트롤러의 `workspace.read`)이 한다.
 */
class WorkspaceAdminService(
    private val store: WorkspaceStore,
    private val time: TimeProvider,
    private val seats: WorkspaceSeatPolicy,
) {
    /** 새 것부터. [cursor] 는 직전 쪽이 돌려준 [AdminWorkspacePage.nextCursor] */
    fun search(query: String?, cursor: String?, size: Int): AdminWorkspacePage {
        val limit = size.coerceIn(1, MAX_PAGE)
        val rows = store.searchWorkspaces(query?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_QUERY), cursor?.takeIf { it.isNotBlank() }, limit + 1)
        val page = rows.take(limit)
        return AdminWorkspacePage(page, if (rows.size > limit) page.last().workspace.id else null)
    }

    fun detail(workspaceId: String): AdminWorkspaceDetail {
        val workspace = store.findWorkspace(workspaceId) ?: throw WorkspaceException(WorkspaceErrorCode.NOT_FOUND)
        val members = store.listMembers(workspaceId)
        return AdminWorkspaceDetail(
            workspace, members.firstOrNull { it.role == WorkspaceRole.OWNER }?.accountId, members.size,
            store.countLiveInvitations(workspaceId, time.now()), seats.limitOf(workspaceId), members,
        )
    }

    companion object {
        const val MAX_PAGE = 100
        const val MAX_QUERY = 100
    }
}
