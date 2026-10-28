package dev.sumin.skeleton.payment.jdbc

import dev.sumin.skeleton.common.discount.DiscountStage
import dev.sumin.skeleton.common.discount.DiscountStageType
import dev.sumin.skeleton.payment.SubscriptionDiscount
import dev.sumin.skeleton.payment.SubscriptionDiscountStatus
import dev.sumin.skeleton.payment.testing.PaymentTestOrders
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.springframework.dao.DataAccessException

/** 두 방언에서: 단계 복사가 순서대로 왕복하고, 쿠폰 정의와 무관하게 복사본이 남으며, 구독 없는 줄은 표가 거절한다. */
class SubscriptionDiscountRowsDbTest {
    @BeforeTest
    fun clean() = PaymentDb.clean()

    private val sub = PaymentTestOrders.subscription(id = "sub_" + "d".repeat(32))
    private fun schedule(stages: List<DiscountStage>) =
        SubscriptionDiscount(sub.id, "coupon", "cpn_1", "1 free then half", stages, 0, SubscriptionDiscountStatus.ACTIVE, PaymentTestOrders.T0, PaymentTestOrders.T0)

    @Test
    fun `the copied stages come back in order with their nulls, and the count and status are the only things that change`() {
        val store = PaymentDb.newStore()
        val stages = listOf(DiscountStage(DiscountStageType.FREE, 0, null, 1), DiscountStage(DiscountStageType.PERCENT, 50, 3_000, 3), DiscountStage(DiscountStageType.FIXED, 500))
        store.inTransaction { tx -> tx.insertSubscriptionIfAbsent(sub); tx.insertSubscriptionDiscount(schedule(stages)) }
        assertEquals(schedule(stages), store.findSubscriptionDiscount(sub.id))

        store.inTransaction { tx ->
            tx.lockSubscription(sub.id)
            tx.updateSubscriptionDiscount(schedule(emptyList()).copy(periodsUsed = 4, status = SubscriptionDiscountStatus.ENDED))
        }

        val after = checkNotNull(store.findSubscriptionDiscount(sub.id))
        assertEquals(stages, after.stages, "the copy is never rewritten")
        assertEquals(4, after.periodsUsed)
        assertEquals(SubscriptionDiscountStatus.ENDED, after.status)
        assertEquals(3, PaymentDb.count("select count(*) from payment_subscription_discount_stages where subscription_id = :s", "s" to sub.id))
    }

    @Test
    fun `the tables refuse a discount or a stage of a subscription that does not exist`() {
        val now = DbTestDatabase.dialect.instantParam(PaymentTestOrders.T0)
        assertFailsWith<DataAccessException> {
            PaymentDb.jdbc.update(
                "insert into payment_subscription_discounts (subscription_id, kind, source_ref, label, periods_used, status, created_at, updated_at) values ('sub_nobody', 'coupon', 'c', 'l', 0, 'ACTIVE', :now, :now)",
                mapOf("now" to now),
            )
        }
        assertFailsWith<DataAccessException> {
            PaymentDb.jdbc.update("insert into payment_subscription_discount_stages (subscription_id, seq, type, value) values ('sub_nobody', 0, 'FREE', 0)", emptyMap<String, Any>())
        }
    }
}
