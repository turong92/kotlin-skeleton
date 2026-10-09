package dev.sumin.skeleton.payment.jdbc

import dev.sumin.skeleton.payment.PaymentEventType
import dev.sumin.skeleton.payment.PaymentOrderStatus
import dev.sumin.skeleton.payment.PaymentTaskTypes
import dev.sumin.skeleton.payment.PaymentWebhookStatus
import dev.sumin.skeleton.payment.WebhookReceipt
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **여러 대 시험 — 웹훅 수신함**(B10). 결제사의 같은 배달이 여러 백엔드로 갈라져 들어와도 줄 하나 · 작업 하나이고, 주문 단위 상한은 인스턴스 수와 무관하게 표의 줄 수로 센다.
 */
class MultiInstanceWebhookDbTest {
    @BeforeTest
    fun clean() = PaymentDb.clean()

    @Test
    fun `the same webhook delivery arriving on three instances leaves one inbox line and one task and settles the order once`() {
        LedgerCluster().use { c ->
            c.rounds(ROUNDS) { round ->
                val order = c.createAuthenticated("k$round", "pay_$round")
                c.provider.settle("pay_$round") // 결제사는 끝냈고, 웹훅이 그 사실을 알린다
                val delivery = c.provider.webhookDelivery("evt-$round", "pay_$round", order.id)

                val receipts = c.raceAcross(4) { inst, _ -> inst.deliver(delivery) }.map { it.second.getOrThrow() }

                assertEquals(1, receipts.count { it == WebhookReceipt.STORED }, "round $round: $receipts")
                assertEquals(receipts.size - 1, receipts.count { it == WebhookReceipt.DUPLICATE }, "round $round: $receipts")
                val lines = c.instances[0].store.listWebhookEvents(null, null, 10)
                assertEquals(1, lines.size, "round $round: one inbox line")
                assertEquals(receipts.size, lines.single().deliveries, "round $round: deliveries counts every delivery that arrived")
                assertEquals(1, PaymentDb.count("select count(*) from jobs where job_type = :t", "t" to PaymentTaskTypes.WEBHOOK), "round $round: one processing task (a RECEIVED line's resend adds none)")

                // 모든 백엔드의 워커가 동시에 폴링한다 — 결제사 조회는 작업 수(1)만큼
                c.drainWorkers()
                assertEquals(1, c.provider.lookupCalls.size, "round $round: one lookup per task")
                assertEquals(PaymentWebhookStatus.PROCESSED, c.instances[1].store.listWebhookEvents(null, null, 10).single().status)
                assertEquals(PaymentOrderStatus.PAID, c.instances[2].order(order.id).status)
                assertEquals(1, c.instances[2].store.listEvents(order.id).count { it.toStatus == PaymentOrderStatus.PAID.name && it.fromStatus != it.toStatus }, "round $round: PAID was written once")
                assertEquals(1, c.instances[2].store.listEvents(order.id).count { it.type == PaymentEventType.FULFILLED }, "round $round")
                assertEquals(1, c.listenersPaid, "round $round: one fulfilment")

                // 처리가 끝난 뒤의 재전송은 작업을 더 넣지 않는다
                c.raceAcross(2) { inst, _ -> inst.deliver(delivery) }.forEach { assertEquals(WebhookReceipt.DUPLICATE, it.second.getOrThrow(), "round $round") }
                assertEquals(1, PaymentDb.count("select count(*) from jobs where job_type = :t", "t" to PaymentTaskTypes.WEBHOOK), "round $round")
            }
        }
    }

    @Test
    fun `new event keys for one order split over three instances stop exactly at the per-order limit - the count is taken under the order lock`() {
        LedgerCluster().use { c ->
            c.rounds(ROUNDS_LIMIT) { round ->
                val capacity = c.properties.webhook.perOrderLimit.capacity
                val order = c.instances[0].create("k$round").order
                val racers = c.size * PER_INSTANCE

                // 열여덟 스레드가 각자 새 이벤트 키 다섯 개를 연달아 보낸다 — 합 90 건, 상한(30)의 세 배
                val results = c.raceAcross(PER_INSTANCE) { inst, t ->
                    (0 until KEYS_PER_THREAD).map { k ->
                        inst.deliver(c.provider.webhookDelivery("evt-$round-${inst.name}-$t-$k", "pay_$round", order.id))
                    }
                }

                val receipts = results.flatMap { it.second.getOrThrow() }
                val stored = PaymentDb.count("select count(*) from payment_webhook_events where resource_type = 'PAYMENT'").toInt()
                assertEquals(racers * KEYS_PER_THREAD, receipts.size)
                assertEquals(capacity, stored, "round $round: exactly the limit - not a line more or less")
                assertEquals(stored, receipts.count { it == WebhookReceipt.STORED }, "round $round: every STORED receipt is a line")
                assertEquals(receipts.size - stored, receipts.count { it == WebhookReceipt.LIMITED }, "round $round: the rest were limited")
                // the signals that hit the limit left one marker row for the window and one deferred job - however many instances raced
                assertEquals(1L, PaymentDb.count("select count(*) from payment_webhook_events where resource_type = 'LIMIT_MARKER'"), "round $round: one marker for the order and window")
                assertEquals(
                    (receipts.size - stored).toLong(),
                    PaymentDb.count("select deliveries from payment_webhook_events where resource_type = 'LIMIT_MARKER'"),
                    "round $round: the marker counts every signal that hit the limit",
                )
                assertEquals(stored + 1L, PaymentDb.count("select count(*) from jobs where job_type = :t", "t" to PaymentTaskTypes.WEBHOOK), "round $round: one task per stored line plus the deferred one of the marker")
            }
        }
    }

    private companion object {
        const val ROUNDS = 25
        const val ROUNDS_LIMIT = 20
        const val PER_INSTANCE = 6
        const val KEYS_PER_THREAD = 5
    }
}
