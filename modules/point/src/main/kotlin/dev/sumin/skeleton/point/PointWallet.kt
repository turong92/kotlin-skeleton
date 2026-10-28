package dev.sumin.skeleton.point

import dev.sumin.skeleton.common.id.SortableIds
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Duration
import java.time.Instant

/**
 * 포인트 지갑. 쓰는 쪽(내부 서비스)은 이 인터페이스만 안다 — 포인트 **사용**은 HTTP 없이 [spend] 뿐이다.
 * 적립 · 차감은 `(sourceType, sourceRef)` 로 멱등이다: 같은 지급 건 · 같은 주문을 두 번 처리해도 한 번만 반영된다.
 */
interface PointWallet {
    fun balance(accountId: String): PointBalance

    /** 같은 `(sourceType, sourceRef)` 가 이미 있으면 null */
    fun earn(accountId: String, amount: Long, expiresAt: Instant?, sourceType: String, sourceRef: String): PointEntry?

    /** 잔액보다 크면 [PointInsufficientException]. 같은 `(sourceType, sourceRef)` 를 다시 부르면 처음 줄을 돌려주고 또 빼지 않는다 */
    fun spend(accountId: String, amount: Long, sourceType: String, sourceRef: String): PointEntry

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

    override fun spend(accountId: String, amount: Long, sourceType: String, sourceRef: String): PointEntry {
        requireAmount(amount)
        return store.spend(accountId, amount, PointEntryKind.SPEND, sourceType, sourceRef, null, time.now())
    }

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
