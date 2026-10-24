package dev.sumin.skeleton.common.authz

/**
 * 운영 행동 한 건. 모양 규칙:
 * - [action] 은 `<모듈>.<대상>.<과거형>` (예: `authz.role.created`)
 * - 대상은 id 로만 — 이메일 · 이름 · 본문 · 사유 원문 · 토큰을 싣지 않는다
 * - 성공한 행동만, 쓰기 트랜잭션 안에서 부른다(나중의 DB 구현이 함께 커밋 · 롤백되게)
 */
data class OperatorAction(
    val actorId: String,
    val action: String,
    val targetType: String,
    val targetId: String,
    val detail: Map<String, String> = emptyMap(),
)

/** 운영 기록 자리. 기본([NoOpOperatorAuditLog])은 아무것도 하지 않는다 — 저장하려면 앱이 빈 하나를 둔다 */
fun interface OperatorAuditLog {
    fun record(action: OperatorAction)
}

class NoOpOperatorAuditLog : OperatorAuditLog {
    override fun record(action: OperatorAction) = Unit
}
