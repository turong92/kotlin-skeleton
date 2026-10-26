package dev.sumin.skeleton.payment.jdbc

import dev.sumin.skeleton.common.dashboard.DashboardAmount
import dev.sumin.skeleton.common.dashboard.DashboardInterval
import dev.sumin.skeleton.common.dashboard.DashboardRange
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource

/** 돈 회귀: 순매출 = 기간 안 결제 − 기간 안 성공 환불, 통화별로 따로. 두 방언에서 같다 */
class DashboardRevenueTest {
    private val week1 = range("2026-10-12T00:00:00Z", "2026-10-19T00:00:00Z")
    private val week2 = range("2026-10-19T00:00:00Z", "2026-10-26T00:00:00Z")

    private val orders = CurrentModeOrders { listOf("scripted" to "TEST") }   // 지금 끼운 결제사 키는 테스트 모드
    private fun metric() = NetRevenueMetric(PaymentDb.jdbc, DbTestDatabase.dialect, orders)
    private val count = PaidCountMetric(PaymentDb.jdbc, DbTestDatabase.dialect, orders)

    @BeforeTest
    fun clean() = PaymentDb.clean()

    private fun range(from: String, to: String) = DashboardRange(Instant.parse(from), Instant.parse(to), ZoneOffset.UTC)

    private fun paid(id: String, currency: String, amount: Long, at: String?, status: String = "PAID", provider: String = "scripted", mode: String = "TEST") {
        val ts = at?.let { DbTestDatabase.dialect.instantParam(Instant.parse(it)) }
        val now = DbTestDatabase.dialect.instantParam(Instant.parse("2026-10-01T00:00:00Z"))
        PaymentDb.jdbc.update(
            """insert into payment_orders (id, account_id, kind, status, provider, provider_mode, currency, amount, paid_amount, item_type, item_ref, order_name,
                                           origin_type, checkout_expires_at, paid_at, created_at, updated_at)
               values (:id, 'acc_1', 'ONE_TIME', :status, :provider, :mode, :currency, :amount, :paidAmount, 'cheer', 'c', 'Cheer', 'CHECKOUT', :now, :paidAt, :now, :now)""",
            MapSqlParameterSource().addValue("id", id).addValue("status", status).addValue("provider", provider).addValue("mode", mode).addValue("currency", currency).addValue("amount", amount)
                .addValue("paidAmount", if (at != null) amount else null).addValue("paidAt", ts).addValue("now", now),
        )
    }

    private fun refund(order: String, amount: Long, currency: String, status: String, completedAt: String?, origin: String = "USER") {
        val now = DbTestDatabase.dialect.instantParam(Instant.parse("2026-10-01T00:00:00Z"))
        PaymentDb.jdbc.update(
            """insert into payment_refunds (id, order_id, status, origin, amount, currency, reason_code, requested_at, completed_at, created_at, updated_at)
               values (:id, :order, :status, :origin, :amount, :currency, 'CUSTOMER_REQUEST', :now, :completedAt, :now, :now)""",
            MapSqlParameterSource().addValue("id", PaymentDb.unique("rfd")).addValue("order", order).addValue("status", status).addValue("origin", origin)
                .addValue("amount", amount).addValue("currency", currency)
                .addValue("completedAt", completedAt?.let { DbTestDatabase.dialect.instantParam(Instant.parse(it)) }).addValue("now", now),
        )
    }

    private fun net(range: DashboardRange) = metric().value(range).data!!
    private fun krw(amount: Long) = DashboardAmount(amount, "KRW")

    @Test
    fun `two payments add up and the count counts them`() {
        paid("ord_1", "KRW", 10000, "2026-10-13T01:00:00Z")
        paid("ord_2", "KRW", 5000, "2026-10-14T01:00:00Z")

        assertEquals(listOf(krw(15000)), net(week1))
        assertEquals(listOf(DashboardAmount(2)), count.value(week1).data)
    }

    @Test
    fun `a refund reduces the period it completed in - the earlier period keeps its number`() {
        paid("ord_1", "KRW", 10000, "2026-10-13T01:00:00Z")
        paid("ord_2", "KRW", 5000, "2026-10-14T01:00:00Z")
        refund("ord_1", 3000, "KRW", "SUCCEEDED", "2026-10-15T01:00:00Z")     // 같은 기간의 부분 환불
        refund("ord_1", 7000, "KRW", "SUCCEEDED", "2026-10-20T01:00:00Z")     // 다음 기간에 나머지 환불

        assertEquals(listOf(krw(12000)), net(week1))
        assertEquals(listOf(krw(-7000)), net(week2))
        assertEquals(listOf(DashboardAmount(2)), count.value(week1).data)       // 환불돼도 결제 건수는 그대로
    }

    @Test
    fun `a provider-side cancel is subtracted, a failed requested or rejected refund is not`() {
        paid("ord_1", "KRW", 10000, "2026-10-13T01:00:00Z")
        refund("ord_1", 1000, "KRW", "SUCCEEDED", "2026-10-14T01:00:00Z", origin = "PROVIDER")
        refund("ord_1", 2000, "KRW", "FAILED", "2026-10-14T01:00:00Z")
        refund("ord_1", 3000, "KRW", "REJECTED", "2026-10-14T01:00:00Z")
        refund("ord_1", 4000, "KRW", "REQUESTED", null)
        refund("ord_1", 5000, "KRW", "PENDING", null)

        assertEquals(listOf(krw(9000)), net(week1))
    }

    @Test
    fun `currencies are never summed and the series follows the configured currency only`() {
        paid("ord_1", "KRW", 10000, "2026-10-13T01:00:00Z")
        paid("ord_2", "USD", 990, "2026-10-13T02:00:00Z")

        assertEquals(listOf(krw(10000), DashboardAmount(990, "USD")), net(week1))
        val daily = { currency: String? -> metric().series(week1, DashboardInterval.DAY, currency).data!!.map { it.value } }
        assertEquals(listOf(0L, 10000L, 0L, 0L, 0L, 0L, 0L), daily("KRW"))       // 시리즈 통화는 조립기가 정해 넘긴다
        assertEquals(listOf(0L, 990L, 0L, 0L, 0L, 0L, 0L), daily("USD"))
        assertEquals(List(7) { 0L }, daily(null))
    }

    @Test
    fun `unpaid orders are not counted and the period is half open`() {
        paid("ord_1", "KRW", 4000, null, status = "PENDING")
        paid("ord_2", "KRW", 4000, null, status = "FAILED")
        paid("ord_3", "KRW", 4000, null, status = "EXPIRED")
        paid("ord_4", "KRW", 1000, "2026-10-12T00:00:00Z")                      // 시작 시각은 포함
        paid("ord_5", "KRW", 2000, "2026-10-19T00:00:00Z")                      // 끝 시각은 다음 기간

        assertEquals(listOf(krw(1000)), net(week1))
        assertEquals(listOf(krw(2000)), net(week2))
        assertEquals(listOf(DashboardAmount(1)), count.value(week1).data)
    }

    @Test
    fun `an order made with another mode is not counted, nor is its refund subtracted - a provider that is not wired counts live orders only`() {
        paid("ord_1", "KRW", 10000, "2026-10-13T01:00:00Z")                                   // 지금 모드(TEST)
        paid("ord_2", "KRW", 5000, "2026-10-13T02:00:00Z", mode = "LIVE")                      // 키를 바꾸기 전 · 다른 모드
        paid("ord_3", "KRW", 7000, "2026-10-13T03:00:00Z", mode = "UNKNOWN")
        paid("ord_4", "KRW", 900, "2026-10-13T04:00:00Z", provider = "retired", mode = "LIVE")  // 꺼진 결제사의 실결제는 센다
        paid("ord_5", "KRW", 800, "2026-10-13T05:00:00Z", provider = "retired", mode = "TEST")  // 꺼진 결제사의 테스트는 안 센다
        refund("ord_1", 1000, "KRW", "SUCCEEDED", "2026-10-14T01:00:00Z")
        refund("ord_2", 2000, "KRW", "SUCCEEDED", "2026-10-14T01:00:00Z")                      // 다른 모드 주문의 환불은 뺄 것이 아니다

        assertEquals(listOf(krw(10000 - 1000 + 900)), net(week1))
        assertEquals(listOf(DashboardAmount(2)), count.value(week1).data)
    }
}
