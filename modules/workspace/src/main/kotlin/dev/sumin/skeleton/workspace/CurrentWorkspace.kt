package dev.sumin.skeleton.workspace

/**
 * 「지금 워크스페이스」 — 앱 컨트롤러가 한 줄로 경계를 건다 (`current.require(WorkspaceRole.MEMBER)`).
 * 요청 헤더 [HEADER] 의 id 를 **믿지 않고** 요청마다 DB 에서 호출자의 멤버십을 읽는다(캐시 없음 - 추방 · 역할 변경이 다음 요청부터 반영된다).
 * 같은 요청 안에서는 한 번만 읽는다.
 */
interface CurrentWorkspace {
    /**
     * 헤더가 없으면 null(문맥 없음). 헤더가 있는데 없는 id · 멤버가 아님 · 닫힘이면 모두 같은 `404 WORKSPACE.NOT_FOUND`.
     * 호출자가 로그인하지 않았으면 `401`.
     */
    fun get(): WorkspaceMember?

    /** 문맥이 필요한 곳 - 헤더가 없으면 `400 WORKSPACE.REQUIRED`, 역할이 [min] 보다 낮으면 `403 WORKSPACE.FORBIDDEN` */
    fun require(min: WorkspaceRole = WorkspaceRole.MEMBER): WorkspaceMember

    companion object {
        const val HEADER = "X-Workspace-Id"
    }
}
