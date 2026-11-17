package dev.sumin.skeleton.point

import dev.sumin.skeleton.common.id.SortableIds
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Duration
import java.time.Instant

/**
 * 포인트 지갑. 쓰는 쪽(내부 서비스)은 이 인터페이스만 안다 — 포인트 **사용**은 HTTP 없이 [spend] 뿐이다.
 * 적립 · 차감은 `(sourceType, sourceRef)` 로 멱등이다: 같은 지급 건 · 같은 주문을 두 번 처리해도 한 번만 반영된다. 같은 키에 **다른 요청**(적립 ↔ 차감, 다른 금액)이 오면 거절한다 - [PointSourceConflictException].
 */
interface PointWallet {
    fun balance(accountId: String): PointBalance

    /** 같은 `(sourceType, sourceRef)` 가 이미 있으면 null(같은 금액일 때만 - 금액이 다르거나 차감이 쓴 키면 [PointSourceConflictException]) */
    fun earn(accountId: String, amount: Long, expiresAt: Instant?, sourceType: String, sourceRef: String): PointEntry?

    /** [earn] 인데 [limit] 을 같은 잠금 안에서 지킨다 — 넘기면 [PointEarnLimitException](아무것도 안 바뀜). 이미 있는 키의 되풀이는 상한과 무관하게 null */
    fun earnWithin(accountId: String, amount: Long, expiresAt: Instant?, sourceType: String, sourceRef: String, limit: PointEarnLimit): PointEntry?

    /** `[from, to)` 에 쌓은 [sourceType] 적립의 합 — 상한의 남은 양을 화면에 보일 때 */
    fun earnedIn(accountId: String, sourceType: String, from: Instant, to: Instant): Long

    /** 잔액보다 크면 [PointInsufficientException]. 같은 `(sourceType, sourceRef)` 를 같은 금액으로 다시 부르면 처음 줄을 돌려주고 또 빼지 않는다 - 금액이 다르거나 적립이 쓴 키면 [PointSourceConflictException] */
    fun spend(accountId: String, amount: Long, sourceType: String, sourceRef: String): PointEntry

    /**
     * 사용 취소(환원) - 차감 `(spendSourceType, spendSourceRef)` 를 [amount](null 이면 아직 안 되돌린 전부)만큼 되돌린다. 부분 취소를 여러 번 해도 된다.
     * 포인트는 원래 묶음에 원래 유효기간으로 돌아가고, 그 사이 지난 묶음 몫은 바로 소멸 줄로 닫힌다. `(sourceType, sourceRef)` 로 멱등이다 -
     * 같은 요청이면 처음 줄을 돌려주고, 다른 요청이면 [PointSourceConflictException]. 원 차감이 없으면 [PointSpendNotFoundException],
     * 취소 합계가 원 차감을 넘으면 [PointCancelExceededException].
     */
    fun cancelSpend(accountId: String, spendSourceType: String, spendSourceRef: String, amount: Long?, sourceType: String, sourceRef: String): PointEntry

    /**
     * 잡아 두기(reserve) - [amount] 를 먼저 잡아 쓸 수 없게 하고 일을 한 뒤 [capture] 로 확정하거나 [release] 로 푼다(카드 승인 → 매입과 같다). 만들고 나서 차감하면 차감이 실패해도 결과가 이미 나간다.
     * 쓸 수 있는 잔액 = 잔액 − 잡힌 합이고 모자라면 [PointInsufficientException]. `(sourceType, sourceRef)` 로 멱등이다(`HELD` 인 같은 요청이면 같은 건, 다르면 · 다른 계정의 키면 [PointSourceConflictException]). 이미 끝난 건(확정 · 풀림 · 만료)의 키로 다시 부르면 [PointReservationNotHeldException] - 새 키로 다시 잡는다.
     * 잡은 채 버려진 건은 `skeleton.point.reservations.hold-timeout` 뒤에 저절로 풀린다 - 일이 그보다 오래 걸리면 안 된다(늦게 확정하면 [PointReservationNotHeldException]).
     * `skeleton.point.reservations.enabled` 가 꺼져 있으면 [IllegalStateException].
     */
    fun reserve(accountId: String, amount: Long, sourceType: String, sourceRef: String): PointReservation

    /**
     * 확정 - 같은 키의 `SPEND` 줄을 만든다. [amount](1 이상, 잡은 양 이하)를 주면 그만큼만 빼고 나머지는 풀린다. 멱등이다: 이미 확정됐으면 같은 줄을 돌려준다.
     * 없거나 다른 계정의 건이면 [PointReservationNotFoundException], 풀렸으면(만료 포함) [PointReservationNotHeldException] - 이때는 결과를 내보내지 않는다. 잡은 양을 넘으면 [PointReservationExceededException].
     */
    fun capture(accountId: String, sourceType: String, sourceRef: String, amount: Long? = null): PointEntry

    /** 풀기 - 잡은 포인트가 돌아온다. 멱등이다(이미 풀렸거나 만료됐으면 그대로). 이미 확정된 건은 풀 수 없다 - [PointReservationNotHeldException] */
    fun release(accountId: String, sourceType: String, sourceRef: String): PointReservation

    /** 기한이 지난 잡힌 건을 푼다. 푼 건 수 */
    fun expireReservations(now: Instant): Int

    fun reservationOf(sourceType: String, sourceRef: String): PointReservation?

    /** 운영자 조정 — 양수는 적립(유효기간 선택), 음수는 차감(잔액보다 크면 [PointInsufficientException]). [reason] 은 필수 */
    fun adjust(accountId: String, delta: Long, reason: String, expiresAt: Instant?, operatorId: String): PointEntry

    /** 유효기간이 지난 묶음을 닫는다. 만든 `EXPIRE` 줄 수 */
    fun expireDue(now: Instant): Int

    fun history(accountId: String, after: PointCursor?, limit: Int): List<PointEntry>

    fun entryOf(sourceType: String, sourceRef: String): PointEntry?
}

class StorePointWallet(
    private val store: PointStore,
    private val time: TimeProvider,
    private val ids: SortableIds = SortableIds.system,
    /** 잡아 두기를 켰을 때만 - 비어 있으면 [reserve] 는 거절한다. 설정 `skeleton.point.reservations` */
    private val holdTimeout: Duration? = null,
) : PointWallet {
    override fun balance(accountId: String): PointBalance {
        val now = time.now()
        return store.balance(accountId, now, now.plus(EXPIRING_SOON))
    }

    override fun earn(accountId: String, amount: Long, expiresAt: Instant?, sourceType: String, sourceRef: String): PointEntry? {
        requireAmount(amount)
        val now = time.now()
        require(expiresAt == null || expiresAt.isAfter(now)) { "expiresAt must be in the future" }
        return store.earn(accountId, amount, expiresAt, PointEntryKind.EARN, sourceType, sourceRef, null, now)
    }

    override fun earnWithin(accountId: String, amount: Long, expiresAt: Instant?, sourceType: String, sourceRef: String, limit: PointEarnLimit): PointEntry? {
        requireAmount(amount)
        val now = time.now()
        require(expiresAt == null || expiresAt.isAfter(now)) { "expiresAt must be in the future" }
        return store.earn(accountId, amount, expiresAt, PointEntryKind.EARN, sourceType, sourceRef, null, now, limit)
    }

    override fun earnedIn(accountId: String, sourceType: String, from: Instant, to: Instant): Long = store.earnedIn(accountId, sourceType, from, to)

    override fun spend(accountId: String, amount: Long, sourceType: String, sourceRef: String): PointEntry {
        requireAmount(amount)
        return store.spend(accountId, amount, PointEntryKind.SPEND, sourceType, sourceRef, null, time.now())
    }

    override fun cancelSpend(accountId: String, spendSourceType: String, spendSourceRef: String, amount: Long?, sourceType: String, sourceRef: String): PointEntry {
        if (amount != null) requireAmount(amount)
        return store.cancelSpend(accountId, spendSourceType, spendSourceRef, amount, sourceType, sourceRef, time.now())
    }

    override fun reserve(accountId: String, amount: Long, sourceType: String, sourceRef: String): PointReservation {
        val hold = checkNotNull(holdTimeout) { "point reservations are off: set skeleton.point.reservations.enabled=true" }
        requireAmount(amount)
        val now = time.now()
        return store.reserve(accountId, amount, sourceType, sourceRef, now.plus(hold), now)
    }

    override fun capture(accountId: String, sourceType: String, sourceRef: String, amount: Long?): PointEntry {
        if (amount != null) requireAmount(amount)
        return store.capture(accountId, sourceType, sourceRef, amount, time.now())
    }

    override fun release(accountId: String, sourceType: String, sourceRef: String): PointReservation = store.release(accountId, sourceType, sourceRef, time.now())

    override fun expireReservations(now: Instant): Int = store.expireReservations(now)

    override fun reservationOf(sourceType: String, sourceRef: String): PointReservation? = store.findReservation(sourceType, sourceRef)

    override fun adjust(accountId: String, delta: Long, reason: String, expiresAt: Instant?, operatorId: String): PointEntry {
        require(delta != 0L) { "delta must not be zero" }
        require(reason.isNotBlank() && reason.length <= MAX_REASON) { "reason is required, up to $MAX_REASON characters" }
        requireAmount(kotlin.math.abs(delta))
        val now = time.now()
        // 조정은 같은 값을 두 번 보내도 두 번 반영되는 게 맞다 - 운영자의 매 행동이 한 줄. 멱등 키는 행동마다 새로 만든다
        val ref = "${operatorId.take(MAX_OPERATOR)}:${ids.newId()}"
        return if (delta > 0) {
            require(expiresAt == null || expiresAt.isAfter(now)) { "expiresAt must be in the future" }
            store.earn(accountId, delta, expiresAt, PointEntryKind.ADJUST, ADJUST_SOURCE, ref, reason, now)
                ?: error("a fresh adjustment key cannot already exist")
        } else {
            require(expiresAt == null) { "a deduction has no expiry" }
            store.spend(accountId, -delta, PointEntryKind.ADJUST, ADJUST_SOURCE, ref, reason, now)
        }
    }

    override fun expireDue(now: Instant): Int = store.expireDue(now)

    override fun history(accountId: String, after: PointCursor?, limit: Int): List<PointEntry> = store.history(accountId, after, limit)

    override fun entryOf(sourceType: String, sourceRef: String): PointEntry? = store.findBySource(sourceType, sourceRef)

    private fun requireAmount(amount: Long) = require(amount in 1..MAX_AMOUNT) { "amount must be 1..$MAX_AMOUNT" }

    companion object {
        const val ADJUST_SOURCE = "ADJUST"
        const val GRANT_SOURCE = "GRANT"

        /** 한 번에 다룰 수 있는 양 - 잔액(bigint)이 넘치지 않게 */
        const val MAX_AMOUNT = 1_000_000_000_000L
        const val MAX_REASON = 200
        private const val MAX_OPERATOR = 100
        val EXPIRING_SOON: Duration = Duration.ofDays(30)
    }
}
