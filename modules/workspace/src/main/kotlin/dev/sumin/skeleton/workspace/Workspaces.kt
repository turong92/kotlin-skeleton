package dev.sumin.skeleton.workspace

/**
 * 앱이 서버 코드에서 쓰는 입구(브라우저에 열려 있지 않다) — 가입 뒤 개인 워크스페이스를 만들거나, 자기 화면에 멤버를 끼울 때.
 */
interface Workspaces {
    /** 워크스페이스를 만들고 만든 사람을 소유자로 둔다(한 트랜잭션). [slug] 가 없으면 이름에서 만든다 */
    fun create(accountId: String, name: String, slug: String? = null): MyWorkspace

    /** 그 계정이 이 워크스페이스의 멤버면 그 줄. 없는 · 닫힌 워크스페이스 · 멤버 아님은 모두 null */
    fun membershipOf(accountId: String, workspaceId: String): WorkspaceMember?

    /** 멤버 줄들(들어온 순). 없는 · 닫힌 워크스페이스는 빈 목록 */
    fun membersOf(workspaceId: String): List<WorkspaceMember>
}
