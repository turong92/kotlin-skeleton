package dev.sumin.skeleton.common.account

/**
 * 계정 삭제 요청을 막는 이유를 모듈이 `account` 를 모르고 내놓는 자리 — `account` 가 삭제 요청 때 모든 구현을 모아, 하나라도 있으면 `409 ACCOUNT.DELETION_BLOCKED` 로 답한다.
 * 막기만 한다(지우기 전 안내) — 그래도 지워지는 경우(운영자 삭제 등)의 뒷정리는 [dev.sumin.skeleton.common.erasure.AccountErasureListener] 몫이다.
 */
fun interface AccountDeletionCheck {
    /** 이 계정의 삭제를 막는 이유들. 없으면 빈 목록 */
    fun blockers(accountId: String): List<DeletionBlocker>
}

/** 삭제를 막는 이유 하나 — [module] 이름, 기계가 읽는 [code], 사람이 찾아갈 [refs](예: 워크스페이스 id) */
data class DeletionBlocker(val module: String, val code: String, val refs: List<String> = emptyList())
