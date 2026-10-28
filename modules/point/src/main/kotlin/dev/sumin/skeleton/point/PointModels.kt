package dev.sumin.skeleton.point

import java.time.Instant

enum class PointEntryKind { EARN, SPEND, EXPIRE, ADJUST }

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

/** 쓸 수 있는 잔액 — 유효기간이 지났는데 소멸 작업이 아직 못 지운 묶음은 뺀 값이다 */
data class PointBalance(val balance: Long, val expiringSoon: PointExpiring?)

/** 내역 목록의 자리 표시 — 최신순에서 이 줄 다음부터 */
data class PointCursor(val createdAt: Instant, val id: String)
