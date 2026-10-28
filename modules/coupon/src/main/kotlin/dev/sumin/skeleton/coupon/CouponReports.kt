package dev.sumin.skeleton.coupon

import dev.sumin.skeleton.common.time.TimeProvider

/** 운영 화면의 한 정의 현황과 발급된 쿠폰 목록 */
class CouponReports(
    private val store: CouponStore,
    private val time: TimeProvider,
) {
    fun stats(definitionId: String): CouponStats {
        requireDefinition(definitionId)
        val counts = store.countByStatus(definitionId, time.now())
        fun n(status: CouponStatus) = counts[status] ?: 0
        return CouponStats(
            issued = counts.values.sum(),
            available = n(CouponStatus.AVAILABLE),
            reserved = n(CouponStatus.RESERVED),
            used = n(CouponStatus.USED),
            expired = n(CouponStatus.EXPIRED),
            revoked = n(CouponStatus.REVOKED),
            discountTotals = store.discountTotals(definitionId),
        )
    }

    /** 새것부터 한 쪽 - 다음 쪽이 있으면 [CouponPage.nextCursor] 가 마지막 항목의 id */
    fun coupons(definitionId: String, status: CouponStatus?, cursor: String?, size: Int): CouponPage {
        val definition = requireDefinition(definitionId)
        val now = time.now()
        val limit = size.coerceIn(1, MAX_SIZE)
        val rows = store.pageByDefinition(definitionId, status, cursor, limit + 1, now)
        val page = rows.take(limit)
        return CouponPage(page.map { CouponView(it, definition, CouponExpiry.statusAt(it, now)) }, if (rows.size > limit) page.last().id else null)
    }

    private fun requireDefinition(id: String) =
        store.findDefinition(id) ?: throw CouponException(CouponErrorCode.DEFINITION_NOT_FOUND, "coupon definition $id not found")

    companion object {
        const val DEFAULT_SIZE = 50
        const val MAX_SIZE = 200
    }
}

data class CouponPage(val items: List<CouponView>, val nextCursor: String?)
