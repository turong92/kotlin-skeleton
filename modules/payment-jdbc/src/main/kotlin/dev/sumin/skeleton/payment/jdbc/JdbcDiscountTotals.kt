package dev.sumin.skeleton.payment.jdbc

import dev.sumin.skeleton.common.discount.CurrencyAmount
import dev.sumin.skeleton.common.discount.DiscountTotals
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * [DiscountTotals] 를 주문의 할인 줄(`payment_order_discounts`)로 답한다 - `CONFIRMED` 줄의 `amount` 합. 전액 환불로 `RESTORED` 가 된 줄, 아직 쥐고 있는(`RESERVED`) 줄,
 * 풀린(`RELEASED`) 줄은 깎아 준 돈이 아니므로 뺀다. 식별 목록은 1000 개씩 끊어 묻고 통화별로 합친다.
 */
class JdbcDiscountTotals(private val jdbc: NamedParameterJdbcTemplate) : DiscountTotals {
    override fun confirmedTotals(kind: String, sourceRefs: Collection<String>): List<CurrencyAmount> {
        val sums = java.util.TreeMap<String, Long>()
        sourceRefs.distinct().chunked(CHUNK).forEach { refs ->
            jdbc.query(
                "select currency, sum(amount) as total from payment_order_discounts where kind = :kind and status = 'CONFIRMED' and source_ref in (:refs) group by currency",
                mapOf("kind" to kind, "refs" to refs),
            ) { rs -> sums.merge(rs.getString("currency").trim(), rs.getLong("total"), Long::plus) }
        }
        return sums.map { (currency, amount) -> CurrencyAmount(currency, amount) }
    }

    private companion object {
        const val CHUNK = 1000
    }
}
