package dev.sumin.skeleton.payment.jdbc

import dev.sumin.skeleton.common.discount.CurrencyAmount
import dev.sumin.skeleton.common.discount.DiscountTotals
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * [DiscountTotals] 를 결제 장부로 답한다. 실제로 깎아 준 돈을 다 센다:
 * - 단건과 구독 **첫 주문**: 주문의 할인 줄(`payment_order_discounts`)의 `CONFIRMED` `amount`. 전액 환불로 `RESTORED` 가 된 줄, 아직 쥐고 있는(`RESERVED`) 줄, 풀린(`RELEASED`) 줄은 깎아 준 돈이 아니므로 뺀다.
 * - 구독 **갱신 주문**: 갱신 주문은 할인 줄 없이 주문 줄의 `discount_amount` 에만 적는다. 그 쿠폰으로 시작한 구독(`payment_subscription_discounts`)의 갱신 주문 중 결제된(`PAID` · `PARTIALLY_REFUNDED`) 것의
 *   `discount_amount` 를 더한다. 전액 환불된(`REFUNDED`) 갱신은 매출이 없던 것으로 보아 뺀다(소진한 회차를 돌려주지는 않지만 깎아 준 돈으로 세지 않는다). 첫 주문은 `INITIAL` 이라 위와 겹치지 않는다.
 * 식별 목록은 1000 개씩 끊어 묻고 통화별로 합친다.
 */
class JdbcDiscountTotals(private val jdbc: NamedParameterJdbcTemplate) : DiscountTotals {
    override fun confirmedTotals(kind: String, sourceRefs: Collection<String>): List<CurrencyAmount> {
        val sums = java.util.TreeMap<String, Long>()
        sourceRefs.distinct().chunked(CHUNK).forEach { refs ->
            jdbc.query(
                "select currency, sum(amount) as total from payment_order_discounts where kind = :kind and status = 'CONFIRMED' and source_ref in (:refs) group by currency",
                mapOf("kind" to kind, "refs" to refs),
            ) { rs -> sums.merge(rs.getString("currency").trim(), rs.getLong("total"), Long::plus) }
            jdbc.query(
                "select o.currency as currency, sum(o.discount_amount) as total from payment_orders o join payment_subscription_discounts s on s.subscription_id = o.origin_ref " +
                    "where s.kind = :kind and s.source_ref in (:refs) and o.origin_type = 'SUBSCRIPTION' and o.kind = 'SUBSCRIPTION_RENEWAL' " +
                    "and o.status in ('PAID', 'PARTIALLY_REFUNDED') and o.discount_amount > 0 group by o.currency",
                mapOf("kind" to kind, "refs" to refs),
            ) { rs -> sums.merge(rs.getString("currency").trim(), rs.getLong("total"), Long::plus) }
        }
        return sums.map { (currency, amount) -> CurrencyAmount(currency, amount) }
    }

    private companion object {
        const val CHUNK = 1000
    }
}
