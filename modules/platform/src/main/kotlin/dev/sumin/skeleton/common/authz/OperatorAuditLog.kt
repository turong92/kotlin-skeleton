package dev.sumin.skeleton.common.authz

/**
 * 운영 행동 한 건. 모양 규칙:
 * - [action] 은 `<모듈>.<대상>.<과거형>` (예: `authz.role.created`)
 * - 대상은 id 로만 — 이메일 · 이름 · 본문 · 사유 원문 · 토큰을 싣지 않는다
 * - 성공한 행동은 쓰기 트랜잭션 안에서 부른다(DB 구현이 함께 커밋 · 롤백되게). 기본 [result] 가 [OperatorResult.SUCCESS] 인 이유
 * - 거절 · 실패([OperatorResult.DENIED] · [OperatorResult.FAILED])를 남기려는 호출자는 롤백되는 트랜잭션 **밖에서** 부른다(안에서 부르면 행동과 함께 사라진다)
 * 접속 IP · 기기 정보는 호출자가 싣지 않는다 — 저장 구현이 지금 처리 중인 요청에서 읽는다.
 */
data class OperatorAction(
    val actorId: String,
    val action: String,
    val targetType: String,
    val targetId: String,
    val detail: Map<String, String> = emptyMap(),
    val result: OperatorResult = OperatorResult.SUCCESS,
)

/** 운영 행동의 결과 — 기록의 「무엇이 됐나」 */
enum class OperatorResult { SUCCESS, DENIED, FAILED }

/** 운영 기록 자리. 기본([NoOpOperatorAuditLog])은 아무것도 하지 않는다 — 저장하려면 `audit-jdbc` 를 붙이거나 앱이 빈 하나를 둔다 */
fun interface OperatorAuditLog {
    fun record(action: OperatorAction)
}

class NoOpOperatorAuditLog : OperatorAuditLog {
    override fun record(action: OperatorAction) = Unit
}
