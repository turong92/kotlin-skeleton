package dev.sumin.skeleton.payment.jdbc

import dev.sumin.skeleton.payment.PaymentEventType
import dev.sumin.skeleton.payment.PaymentOrder
import dev.sumin.skeleton.payment.PaymentOrderNotPayableException
import dev.sumin.skeleton.payment.PaymentOrderStatus
import dev.sumin.skeleton.payment.PaymentTaskRun
import dev.sumin.skeleton.payment.PaymentTaskTypes
import java.time.Duration
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 서비스(`payment`)를 진짜 저장소 위에서 경주시킨다(스펙 §13.2): 교착 없이 끝나고(`race` 의 60초 제한이 막힘을 잡는다) 승자는 하나, 상태는 한 곳으로 모인다.
 * 락은 주문 줄 하나라서 이 경주들은 모두 `lockOrder` 에서 줄을 선다.
 */
class PaymentLedgerRaceDbTest {
    private val ttl = Duration.ofMinutes(30)

    @BeforeTest
    fun clean() = PaymentDb.clean()


    @Test
    fun `eight fulfil handlers racing on one paid order call the listener exactly once`() {
        val kit = JdbcLedgerKit()
        kit.listener.failPaid(1) // 확정 직후의 첫 지급 시도는 실패 — 주문은 PAID 이고 fulfilled_at 은 비어 있다
        val order = kit.createAuthenticated()
        kit.orders.confirm("acc_1", order.id, "pay_1", order.amount)
        assertEquals(PaymentOrderStatus.PAID, kit.order(order.id).status)
        assertEquals(null, kit.order(order.id).fulfilledAt)

        val results = race(8) { kit.fulfil.handle(PaymentTaskRun(PaymentTaskTypes.ORDER_FULFIL, order.id, 1, 5)) }

        assertTrue(results.all { it.isSuccess }, results.mapNotNull { it.exceptionOrNull() }.toString())
        assertEquals(1, kit.listener.paid.size, "the app's onPaid ran once")
        assertEquals(2, kit.listener.attempts.get(), "one planted failure + the one that went through")
        assertTrue(kit.order(order.id).fulfilledAt != null)
        assertEquals(1, eventsOf(kit, order, PaymentEventType.FULFILLED).size)
    }

    @Test
    fun `a reconcile job and a confirm response racing settle the order once`() {
        val kit = JdbcLedgerKit()
        val order = kit.createAuthenticated()
        kit.gateway.duringConfirm = {
            kit.gateway.duringConfirm = null
            kit.gateway.settle("pay_1") // 결제사는 이미 끝냈다
            kit.reconcile.handle(PaymentTaskRun(PaymentTaskTypes.ORDER_RECONCILE, order.id, 1, 12)) // 조회 작업이 먼저 맞춘다 (락은 서로 떨어져 있다)
        }

        val result = kit.orders.confirm("acc_1", order.id, "pay_1", order.amount)

        assertEquals(PaymentOrderStatus.PAID, result.status)
        assertEquals(1, kit.listener.paid.size)
        assertEquals(1, PaymentDb.count("select count(*) from jobs where job_type = :t", "t" to PaymentTaskTypes.ORDER_FULFIL), "one fulfil job")
    }

    private fun eventsOf(kit: JdbcLedgerKit, order: PaymentOrder, type: PaymentEventType) = kit.store.listEvents(order.id).filter { it.type == type }
}
