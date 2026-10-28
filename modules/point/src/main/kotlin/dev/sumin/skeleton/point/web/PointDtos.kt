package dev.sumin.skeleton.point.web

import dev.sumin.skeleton.point.PointBalance
import dev.sumin.skeleton.point.PointEntry
import dev.sumin.skeleton.point.PointEntryKind
import java.time.Instant

data class PointBalanceResponse(val balance: Long, val expiringSoon: ExpiringSoon?) {
    data class ExpiringSoon(val amount: Long, val before: Instant)

    companion object {
        fun of(balance: PointBalance) =
            PointBalanceResponse(balance.balance, balance.expiringSoon?.let { ExpiringSoon(it.amount, it.before) })
    }
}

data class PointEntryResponse(
    val id: String,
    val delta: Long,
    val kind: PointEntryKind,
    val reason: String?,
    val expiresAt: Instant?,
    val createdAt: Instant,
) {
    companion object {
        fun of(entry: PointEntry) = PointEntryResponse(entry.id, entry.delta, entry.kind, entry.reason, entry.expiresAt, entry.createdAt)
    }
}

/** 운영 화면의 한 계정 — 잔액과 내역(최신순, 첫 쪽) */
data class AdminPointAccountResponse(val balance: Long, val entries: List<PointEntryResponse>)

data class PointAdjustmentRequest(val delta: Long? = null, val reason: String? = null, val expiresInDays: Int? = null)
