package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.PaymentOrderStatus.CANCELED
import dev.sumin.skeleton.payment.PaymentOrderStatus.CONFIRMING
import dev.sumin.skeleton.payment.PaymentOrderStatus.FAILED
import dev.sumin.skeleton.payment.PaymentOrderStatus.PAID
import dev.sumin.skeleton.payment.PaymentOrderStatus.PARTIALLY_REFUNDED
import dev.sumin.skeleton.payment.PaymentOrderStatus.PENDING
import dev.sumin.skeleton.payment.PaymentOrderStatus.REFUNDED
import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import dev.sumin.skeleton.payment.testing.ScriptedGateway.ConfirmScript
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** 스펙 §13.2 를 **서비스 입구에서** 본다: 입구(`confirm` · `abandon` · `declined` · `converge`)마다 여덟 상태에서 무슨 일이 일어나야 하는지. 전이표 자체는 [PaymentOrderTransitionsTest]. */
class OrderEntryStateMatrixTest {
    private val paidFamily = setOf(PAID, PARTIALLY_REFUNDED, REFUNDED)

    /** 주문 하나를 만들고 그 줄을 [status] 로 직접 놓는다(전이표를 거치지 않는 시험용 배치). 결제 키는 `PENDING` 만 비어 있다. */
    private fun orderIn(kit: PaymentLedgerKit, status: PaymentOrderStatus): PaymentOrder {
        val created = kit.create().order
        kit.store.inTransaction { tx ->
            tx.updateOrder(
                tx.lockOrder(created.id)!!.copy(
                    status = status,
                    providerPaymentId = if (status == PENDING) null else "pay_1",
                    paidAmount = if (status in paidFamily) created.amount.amount else null,
                ),
            )
        }
        return kit.order(created.id)
    }

    private fun snapshot(status: ProviderPaymentStatus, order: PaymentOrder) = PaymentSnapshot(
        "pay_1", order.id, status, order.amount, order.amount, "CARD", java.time.Instant.parse("2026-10-08T00:00:05Z"), emptyList(), PaymentCard("HYUNDAI", "1234"),
    )

    @TestFactory
    fun `confirm on every status that is not pending never calls the provider and only a paid order with the same payment id answers`(): List<DynamicTest> =
        PaymentOrderStatus.entries.filter { it != PENDING }.flatMap { status ->
            listOf("pay_1", "pay_other").map { paymentId ->
                DynamicTest.dynamicTest("confirm with $paymentId on $status") {
                    val kit = PaymentLedgerKit()
                    val order = orderIn(kit, status)
                    val eventsBefore = kit.store.listEvents(order.id)
                    val answers = if (paymentId == "pay_1") status in paidFamily || status == CONFIRMING else status == CONFIRMING

                    if (answers) {
                        assertEquals(order, kit.orders.confirm("acc_1", order.id, paymentId, order.amount), "the row is returned as it is")
                    } else {
                        assertFailsWith<PaymentOrderNotPayableException> { kit.orders.confirm("acc_1", order.id, paymentId, order.amount) }
                    }

                    assertEquals(0, kit.gateway.confirmCalls.size)
                    assertEquals(order, kit.order(order.id))
                    assertEquals(eventsBefore, kit.store.listEvents(order.id))
                }
            }
        }

    @TestFactory
    fun `abandon closes a pending order as canceled and leaves every other status as it is`(): List<DynamicTest> =
        PaymentOrderStatus.entries.map { status ->
            DynamicTest.dynamicTest("abandon on $status") {
                val kit = PaymentLedgerKit()
                val order = orderIn(kit, status)
                val eventsBefore = kit.store.listEvents(order.id)

                val result = kit.orders.abandon("acc_1", order.id)

                if (status == PENDING) {
                    assertEquals(CANCELED, result.status)
                } else {
                    assertEquals(order, result)
                    assertEquals(eventsBefore, kit.store.listEvents(order.id), "no history line for a no-op")
                }
            }
        }

    /** 결제사의 끝 상태 하나가 여덟 주문 상태에 닿으면 어디가 바뀌는지 — 확정 거절(`declined`)과 조회 결과(`converge`) 모두. */
    @TestFactory
    fun `a provider verdict moves only the statuses the table allows and never touches the paid family`(): List<DynamicTest> =
        PaymentOrderStatus.entries.flatMap { status ->
            listOf(null, ProviderPaymentStatus.FAILED, ProviderPaymentStatus.EXPIRED, ProviderPaymentStatus.IN_PROGRESS, ProviderPaymentStatus.PAID).map { verdict -> // null = 확정 거절
                DynamicTest.dynamicTest("${verdict ?: "DECLINED"} on $status") {
                    val kit = PaymentLedgerKit()
                    val order = orderIn(kit, status)
                    kit.store.inTransaction { tx ->
                        val locked = tx.lockOrder(order.id)!!
                        if (verdict == null) {
                            kit.convergence.declined(tx, locked, "REJECT_CARD_COMPANY", ConvergeContext(PaymentMode.TEST, ConvergeSource.CONFIRM))
                        } else {
                            kit.convergence.converge(tx, locked, snapshot(verdict, order), ConvergeContext(PaymentMode.TEST, ConvergeSource.RECONCILE))
                        }
                    }

                    val closes = status == CONFIRMING && verdict != ProviderPaymentStatus.IN_PROGRESS && verdict != ProviderPaymentStatus.PAID
                    val pays = verdict == ProviderPaymentStatus.PAID && status !in paidFamily
                    when {
                        closes -> assertEquals(FAILED, kit.order(order.id).status)
                        pays -> {
                            assertEquals(PAID, kit.order(order.id).status)
                            assertEquals(1, kit.tasks.all(PaymentTaskTypes.ORDER_FULFIL).size)
                        }
                        else -> {
                            assertEquals(order, kit.order(order.id))
                            assertEquals(0, kit.tasks.all(PaymentTaskTypes.ORDER_FULFIL).size)
                        }
                    }
                }
            }
        }

    @Test
    fun `a decline that arrives after the reconcile job already paid the order does not undo the money and is not reported as a decline`() {
        val kit = PaymentLedgerKit()
        val order = kit.createAuthenticated()
        kit.gateway.script(ConfirmScript.Decline())
        kit.gateway.duringConfirm = {
            kit.gateway.duringConfirm = null
            kit.gateway.settle("pay_1") // 결제사는 실제로 끝냈다
            kit.reconcile.handle(PaymentTaskRun(PaymentTaskTypes.ORDER_RECONCILE, order.id, 1, 12))
        }

        val result = kit.orders.confirm("acc_1", order.id, "pay_1", order.amount) // 던지지 않는다

        assertEquals(PAID, result.status)
        assertNull(kit.order(order.id).failureCode)
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_FULFIL))
        assertEquals(1, kit.listener.paid.size)
    }

    @Test
    fun `two orders claiming one provider payment id at the same moment - the loser is refused at the claim and the provider is called once`() {
        val kit = PaymentLedgerKit()
        val a = kit.create(key = "a").order
        val b = kit.create(key = "b").order
        kit.gateway.authenticate("pay_1", a.id, a.amount)
        val aHoldsTheKey = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        val slowTasks = PaymentTasks { task ->
            if (task.type == PaymentTaskTypes.ORDER_RECONCILE && task.subjectId == a.id) {
                aHoldsTheKey.countDown()
                releaseA.await(10, TimeUnit.SECONDS)
            }
            kit.tasks.enqueue(task)
        }
        val orders = PaymentOrders(kit.store, slowTasks, kit.router, kit.catalog, kit.convergence, kit.fulfillment, kit.writer, kit.properties)
        val pool = Executors.newFixedThreadPool(2)
        val bThread = AtomicReference<Thread>()
        try {
            val first = pool.submit<PaymentOrder> { orders.confirm("acc_1", a.id, "pay_1", a.amount) }
            assertTrue(aHoldsTheKey.await(5, TimeUnit.SECONDS), "A claimed pay_1 and is still inside its claim transaction")
            val second = pool.submit<Throwable?> {
                bThread.set(Thread.currentThread())
                try {
                    orders.confirm("acc_1", b.id, "pay_1", b.amount)
                    null
                } catch (e: PaymentOrderNotPayableException) {
                    e
                }
            }
            // B 는 A 가 아직 커밋하지 않은 때 "주인 없음"을 보고 쓰기에서 A 의 결제 키 잠금을 기다린다
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (bThread.get()?.state != Thread.State.WAITING && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(Thread.State.WAITING, bThread.get()?.state, "B reached the unique key while A still holds it")
            releaseA.countDown()

            assertEquals(PAID, first.get(10, TimeUnit.SECONDS).status)
            assertTrue(second.get(10, TimeUnit.SECONDS) is PaymentOrderNotPayableException, "the key conflict is read as an order that cannot be paid")
        } finally {
            releaseA.countDown()
            pool.shutdownNow()
        }

        assertEquals(1, kit.gateway.confirmCalls.size, "the provider heard about pay_1 once")
        assertEquals(PENDING, kit.order(b.id).status)
        assertNull(kit.order(b.id).providerPaymentId)
    }
}
