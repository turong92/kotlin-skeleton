package dev.sumin.skeleton.workspace

/**
 * 워크스페이스의 좌석(멤버 + 대기 초대) 상한을 앱이 정하는 자리 — 값은 앱의 상품이 정한다(예: 구독 플랜). 모듈은 세고 막기만 한다.
 * 빈이 없으면 상한이 없다(무제한). 상한은 초대 · 수락에서 검사한다(초대 조각).
 */
fun interface WorkspaceSeatPolicy {
    /** 이 워크스페이스의 좌석 상한. null 이면 무제한 */
    fun limitOf(workspaceId: String): Int?
}
