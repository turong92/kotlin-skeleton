package dev.sumin.skeleton.payment.jdbc

import dev.sumin.skeleton.payment.PaymentCard
import dev.sumin.skeleton.payment.PaymentOrderStatus
import dev.sumin.skeleton.payment.testing.ScriptedGateway.ConfirmScript
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.assertj.core.api.Assertions.assertThat

/**
 * 심은 값 스캔(스펙 §3 19 · §13.2): 결제사가 실수로 전체 카드 번호 · 이름 · 이메일 · 전화를 흘려도 **어느 표의 어느 칸에도** 남지 않는다.
 * 가짜 결제사가 거절 코드에 문장을, 카드 칸에 전체 번호와 이름을 심어 보내고, 진짜 DB 의 네 표를 통째로 훑는다. 통과하려면 서비스의 걸름(`LedgerValues`)과
 * 칸 모양(`varchar(4)`)이 함께 일한다 — 한쪽이 빠지면 여기서 드러난다.
 */
class PaymentPlantedValuesDbTest {
    @BeforeTest
    fun clean() = PaymentDb.clean()

    /**
     * 심은 조각 — **16진수로 나올 수 없는 것만**: 주문 id · 환불 id · 해시는 무작위 16진수라 `4111` 같은 짧은 숫자 조각이 우연히 들어갈 수 있다(`-` · 이름 글자는 16진수가 아니다).
     * 전체 카드 번호는 16자리 숫자열이라 무작위 16진수 id(32자 중 숫자만 16개가 이어질 확률은 사실상 0)에 우연히 나올 수 없다 — **대시 없는 모양**(②가 카드 칸에 심는 값)과 대시 있는 모양을 둘 다 찾는다.
     * 두 시험이 이 목록을 함께 쓴다.
     */
    private val planted = listOf("4111111111111111", "4111-1111", "1111-1111", "Kim", "Minsu", "kim@example.com", "010-1234-5678")

    @Test
    fun `no table holds a planted card number, name, e-mail or phone - and the card columns hold only issuer code and last four`() {
        val kit = JdbcLedgerKit()

        // ① 거절 코드에 문장이 섞여 왔다
        val declined = kit.createAuthenticated(key = "k-declined", paymentKey = "pay_declined")
        kit.gateway.script(ConfirmScript.Decline(code = "card 4111-1111-1111-1111 of Kim Minsu kim@example.com 010-1234-5678"))
        runCatching { kit.orders.confirm("acc_1", declined.id, "pay_declined", declined.amount) }
        assertEquals(PaymentOrderStatus.FAILED, kit.order(declined.id).status)
        assertThat(kit.order(declined.id).failureCode).`as`("a sentence is not a code - the stored failure code is our own code").matches("[A-Z0-9_.-]{1,64}")

        // ② 카드 칸에 전체 번호 · 이름이 섞여 왔다
        val paid = kit.create(key = "k-paid").order
        kit.gateway.authenticate("pay_paid", paid.id, paid.amount, card = PaymentCard("Kim Minsu card", "4111111111111111"))
        kit.orders.confirm("acc_1", paid.id, "pay_paid", paid.amount)
        val stored = kit.order(paid.id)
        assertEquals(PaymentOrderStatus.PAID, stored.status)
        assertNull(stored.cardIssuer, "an issuer that is not a code is dropped")
        assertNull(stored.cardLast4, "a number longer than four characters is dropped, never truncated into something that looks right")

        // ③ 정상 카드는 두 칸이 그대로 남는다 (비교용)
        val normal = kit.createAuthenticated(key = "k-normal", paymentKey = "pay_normal")
        kit.orders.confirm("acc_1", normal.id, "pay_normal", normal.amount)
        assertEquals("HYUNDAI" to "1234", kit.order(normal.id).let { it.cardIssuer to it.cardLast4 })

        val everything = listOf("payment_orders", "payment_refunds", "payment_events", "payment_webhook_events", "jobs").flatMap { table ->
            PaymentDb.jdbc.queryForList("select * from $table", emptyMap<String, Any>()).flatMap { row -> row.values.mapNotNull { it?.toString() } }
        }
        assertThat(everything).isNotEmpty
        planted.forEach { secret -> assertThat(everything.filter { it.contains(secret, ignoreCase = true) }).`as`("planted value $secret").isEmpty() }
        assertThat(PaymentDb.jdbc.queryForList("select card_last4 from payment_orders where card_last4 is not null", emptyMap<String, Any>(), String::class.java))
            .allMatch { it!!.length <= 4 }
    }

    @Test
    fun `no table holds a value planted in a webhook body or in the exception of a failed lookup`() {
        val kit = JdbcLedgerKit()
        val created = kit.createAuthenticated()
        kit.gateway.script(ConfirmScript.TimeoutApplied())
        kit.orders.confirm("acc_1", created.id, "pay_1", created.amount)
        kit.gateway.failLookups(1) { IllegalStateException("card 4111-1111-1111-1111 4111111111111111 of Kim Minsu kim@example.com 010-1234-5678") }
        val plantedBody = "Kim Minsu kim@example.com 010-1234-5678 4111-1111-1111-1111 4111111111111111"

        kit.deliver(kit.gateway.webhookDelivery(eventKey = "evt-planted", orderId = created.id, claimedStatus = "CANCELED", plant = plantedBody))
        val eventId = kit.store.listWebhookEvents(null, null, 1).single().id
        runCatching { kit.webhook.handle(dev.sumin.skeleton.payment.PaymentTaskRun(dev.sumin.skeleton.payment.PaymentTaskTypes.WEBHOOK, eventId.toString(), 1, 5)) } // 조회 실패 → 우리 코드만 남는다
        assertEquals("LOOKUP_UNAVAILABLE", kit.store.findWebhookEvent(eventId)!!.lastError)
        kit.webhook.handle(dev.sumin.skeleton.payment.PaymentTaskRun(dev.sumin.skeleton.payment.PaymentTaskTypes.WEBHOOK, eventId.toString(), 2, 5))

        val inbox = PaymentDb.jdbc.queryForList("select * from payment_webhook_events", emptyMap<String, Any>()).flatMap { row -> row.values.mapNotNull { it?.toString() } }
        val everything = listOf("payment_orders", "payment_refunds", "payment_events", "payment_webhook_events", "jobs").flatMap { table ->
            PaymentDb.jdbc.queryForList("select * from $table", emptyMap<String, Any>()).flatMap { row -> row.values.mapNotNull { it?.toString() } }
        }
        assertThat(inbox).isNotEmpty
        assertThat(PaymentDb.jdbc.queryForObject("select count(*) from payment_webhook_events where payload is not null", emptyMap<String, Any>(), Long::class.java)).`as`("the body is not stored").isZero
        (planted + "CANCELED").forEach { secret ->
            assertThat(everything.filter { it.contains(secret, ignoreCase = true) }).`as`("planted value $secret in any table, jobs included").isEmpty()
        }
        assertEquals(PaymentOrderStatus.PAID, kit.order(created.id).status)
    }
}
