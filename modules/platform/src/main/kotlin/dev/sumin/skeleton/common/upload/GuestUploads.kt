package dev.sumin.skeleton.common.upload

/**
 * 로그인하지 않은 사람이 올린 파일의 저장 키인가 - storage 가 구현하고(발급한 꼴 + MAC 이 맞는 키만 참), 비회원 첨부를 받는 쪽(예: board 의 `canGuestAttach`)이 쓴다.
 * 구현이 없으면 호출하는 쪽은 지금까지처럼 키를 검사하지 않는다.
 */
fun interface GuestUploadKeys {
    fun isGuestKey(key: String): Boolean
}

/**
 * 저장 키 중 지금 어딘가(글 · 댓글 · 고친 판 …)에 붙어 있는 것 - 앱의 저장소 모듈이 구현하고(board-jdbc), storage 의 고아 회수가
 * 붙어 있지 않은 비회원 업로드만 지우려고 묻는다. 구현이 없으면 회수는 아무것도 지우지 않는다.
 */
fun interface UploadedKeyUsage {
    /** [keys] 중 사용 중인 것의 부분집합 */
    fun inUse(keys: Collection<String>): Set<String>
}
