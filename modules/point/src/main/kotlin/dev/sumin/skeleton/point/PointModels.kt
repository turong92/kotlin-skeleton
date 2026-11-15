package dev.sumin.skeleton.point

import java.time.Instant

/**
 * [RESTORE] = 사용 취소(환원): 차감을 되돌려 양수 [PointEntry.delta] 로 잔액에 더한다. 포인트는 원래 묶음에 원래 유효기간으로 돌아가고, 그 사이 유효기간이 지난 묶음 몫은 같은 시각의
 * [EXPIRE] 줄이 바로 닫는다(사용자에게는 환원 줄 + 소멸 줄이 보인다). 환원 줄 자신은 묶음이 아니라 [PointEntry.remaining] 이 0 이다.
 */
enum class PointEntryKind { EARN, SPEND, EXPIRE, ADJUST, RESTORE }

/**
 * 원장 한 줄. 적립 묶음(`EARN` · 양수 `ADJUST`)은 [remaining] 에 아직 안 쓴 양을 들고, 차감 · 소멸 줄은 [delta] 가 음수이고 [remaining] 은 0 이다.
 * `(sourceType, sourceRef)` 는 원장 전체에서 하나 — 같은 지급 건 · 같은 주문을 두 번 처리해도 한 줄로 접힌다.
 */
data class PointEntry(
    val id: String,
    val accountId: String,
    val delta: Long,
    val kind: PointEntryKind,
    val remaining: Long,
    val expiresAt: Instant?,
    val sourceType: String,
    val sourceRef: String,
    val reason: String?,
    val createdAt: Instant,
)

/** [amount] 포인트가 [before] 전에 사라진다 */
data class PointExpiring(val amount: Long, val before: Instant)

/** 쓸 수 있는 잔액 — 유효기간이 지났는데 소멸 작업이 아직 못 지운 묶음과 [held](잡혀 있는 양, 확정되거나 풀리기 전)는 뺀 값이다 */
data class PointBalance(val balance: Long, val expiringSoon: PointExpiring?, val held: Long = 0)

/** 내역 목록의 자리 표시 — 최신순에서 이 줄 다음부터 */
data class PointCursor(val createdAt: Instant, val id: String)

/** [HELD] 잡혀 있다 → [CAPTURED] 확정(차감 줄이 생겼다) · [RELEASED] 풀었다 · [EXPIRED] 기한이 지나 저절로 풀렸다 */
enum class PointReservationStatus { HELD, CAPTURED, RELEASED, EXPIRED }

/**
 * 잡아 둔 포인트 한 건. [amount] 만큼이 잔액에서 빠져 있고(쓸 수 없다) 확정 · 풀기 · 기한 중 하나로 끝난다. `(sourceType, sourceRef)` 는 원장의 키와 같은 공간이다 -
 * 확정하면 같은 키의 `SPEND` 줄이 생긴다. [capturedAmount] 는 확정된 양(부분 확정이면 [amount] 보다 작다), [holdUntil] 이 지나도 아직 못 훑었으면 [PointReservationStatus.HELD] 로 남아 있을 수 있다.
 */
data class PointReservation(
    val id: String,
    val accountId: String,
    val amount: Long,
    val capturedAmount: Long?,
    val status: PointReservationStatus,
    val sourceType: String,
    val sourceRef: String,
    val holdUntil: Instant,
    val createdAt: Instant,
    val settledAt: Instant?,
)
