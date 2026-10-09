package dev.sumin.skeleton.payment.jdbc

import dev.sumin.skeleton.common.erasure.AccountTombstone
import dev.sumin.skeleton.common.erasure.ErasureRequest
import dev.sumin.skeleton.payment.CreateOrderCommand
import dev.sumin.skeleton.payment.IdempotencyKey
import dev.sumin.skeleton.payment.PaymentAccountErasure
import dev.sumin.skeleton.payment.PaymentOrderStatus
import dev.sumin.skeleton.payment.PaymentProperties
import dev.sumin.skeleton.payment.PaymentTaskRun
import dev.sumin.skeleton.payment.PaymentTaskTypes
import dev.sumin.skeleton.payment.testing.ScriptedGateway
import java.time.Duration
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.transaction.support.TransactionTemplate

/**
 * 계정 탈퇴 고리(스펙 §8.2 · §13.2)를 진짜 DB 에서 — 주문 줄은 남고 카드 두 칸만 지워지며, 두 번째 호출은 아무것도 바꾸지 않고, 계정 모듈의 바깥 트랜잭션 안에서도 돈다.
 * 열 분류 시험(`PaymentSchemaDbTest`)이 「지워지는 개인정보는 `card_issuer` · `card_last4` 뿐」임을 말하고, 여기서는 그 두 칸이 실제로 비워지는 것을 본다.
 */
class PaymentErasureDbTest {
    private val kit = JdbcLedgerKit()

    @BeforeTest
    fun clean() = PaymentDb.clean()

    private fun erasure(mode: PaymentProperties.Erasure.AccountIdMode = PaymentProperties.Erasure.AccountIdMode.KEEP) = PaymentAccountErasure(kit.store, kit.writer, mode)

    private fun request(account: String) = ErasureRequest(account, AccountTombstone.of(account))

    private fun paidFor(account: String, key: String) =
        kit.orders.create(CreateOrderCommand(account, "cheer", "cheer-3000", IdempotencyKey(key), 1)).order.also {
            kit.gateway.authenticate("pay_$key", it.id, it.amount)
            kit.orders.confirm(account, it.id, "pay_$key", it.amount)
        }

    private fun cardColumns(account: String) =
        PaymentDb.jdbc.queryForList("select card_issuer, card_last4, account_erased_at, status from payment_orders where account_id = :a order by id", mapOf("a" to account))

    @Test
    fun `the card columns of every order of the account are empty afterwards, the lines and everything else stay, and a second call changes nothing`() {
        val first = paidFor("acc_gone", "k1")
        val second = paidFor("acc_gone", "k2")
        val unpaid = kit.create("acc_gone", key = "k3").order
        val other = paidFor("acc_stay", "k4")
        assertNotNull(kit.order(first.id).cardIssuer)

        erasure().erase(request("acc_gone"))

        listOf(first, second).forEach {
            val row = kit.order(it.id)
            assertNull(row.cardIssuer)
            assertNull(row.cardLast4)
            assertNotNull(row.accountErasedAt)
            assertEquals(PaymentOrderStatus.PAID, row.status)
            assertEquals("pay_k${if (it.id == first.id) 1 else 2}", row.providerPaymentId, "the provider payment number stays - the record of the payment")
            assertEquals("acc_gone", row.accountId)
        }
        assertEquals(PaymentOrderStatus.CANCELED, kit.order(unpaid.id).status)
        assertEquals("HYUNDAI", kit.order(other.id).cardIssuer)
        val before = PaymentDb.jdbc.queryForList("select * from payment_orders order by id", emptyMap<String, Any>())
        val events = PaymentDb.count("select count(*) from payment_events")

        erasure().erase(request("acc_gone"))

        assertEquals(before, PaymentDb.jdbc.queryForList("select * from payment_orders order by id", emptyMap<String, Any>()))
        assertEquals(events, PaymentDb.count("select count(*) from payment_events"))
        assertTrue(cardColumns("acc_gone").all { it["card_issuer"] == null && it["card_last4"] == null })
    }

    @Test
    fun `it runs inside the account module's outer transaction and is rolled back with it`() {
        val paid = paidFor("acc_gone", "k1")
        val outer = TransactionTemplate(PaymentDb.transactionManager)

        outer.execute { erasure().erase(request("acc_gone")) }
        assertNull(kit.order(paid.id).cardIssuer)

        val again = paidFor("acc_gone", "k2")
        runCatching { outer.execute { erasure().erase(request("acc_gone")); error("the account module failed another listener") } }
        // 한 문장 update 는 바깥 트랜잭션에 합류해 함께 되돌려진다 — 다음 주기에 다시 불려도 같은 결과
        assertNotNull(kit.order(again.id).cardIssuer)
        erasure().erase(request("acc_gone"))
        assertNull(kit.order(again.id).cardIssuer)
    }

    @Test
    fun `a confirming order stays and when it turns paid after the erasure the card is not written`() {
        val created = kit.createAuthenticated("k1", "pay_late")
        kit.gateway.script(ScriptedGateway.ConfirmScript.TimeoutNotApplied)
        kit.orders.confirm("acc_1", created.id, "pay_late", created.amount)
        assertEquals(PaymentOrderStatus.CONFIRMING, kit.order(created.id).status)

        erasure().erase(request("acc_1"))
        assertEquals(PaymentOrderStatus.CONFIRMING, kit.order(created.id).status)

        kit.gateway.settle("pay_late")
        kit.time.advance(Duration.ofMinutes(5))
        kit.reconcile.handle(PaymentTaskRun(PaymentTaskTypes.ORDER_RECONCILE, created.id, 1, 12))

        val paid = kit.order(created.id)
        assertEquals(PaymentOrderStatus.PAID, paid.status)
        assertNull(paid.cardIssuer)
        assertNull(paid.cardLast4)
    }

    @Test
    fun `TOMBSTONE rewrites the account's own orders and what it wrote there, and leaves the operator ids on other accounts' orders`() {
        val own = paidFor("acc_gone", "k1")
        val others = paidFor("acc_other", "k2")
        kit.refunds.request("acc_other", others.id, "CUSTOMER_REQUEST", 1000, IdempotencyKey("r1"))
        val line = kit.store.listRefunds(others.id).single()
        kit.refunds.reject("acc_gone", line.id)
        val tombstone = AccountTombstone.of("acc_gone")

        erasure(PaymentProperties.Erasure.AccountIdMode.TOMBSTONE).erase(request("acc_gone"))

        assertEquals(tombstone, kit.order(own.id).accountId)
        assertEquals(0, PaymentDb.count("select count(*) from payment_events where order_id = :o and actor_id = 'acc_gone'", "o" to own.id))
        assertEquals("acc_other", kit.order(others.id).accountId)
        assertEquals("acc_gone", kit.store.findRefund(line.id)!!.decidedBy)
        erasure(PaymentProperties.Erasure.AccountIdMode.TOMBSTONE).erase(request("acc_gone")) // 멱등
        assertEquals(tombstone, kit.order(own.id).accountId)
    }
}
