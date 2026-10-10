package dev.sumin.skeleton.app.sample.cheer

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.payment.testing.ScriptedGateway.ConfirmScript
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 응원하기의 여정(스펙 §13.2 `apps/sample` 통합): 가입한 계정이 서버 목록에서 금액을 골라 주문 → 승인 → 응원 기록(`PAID`, 카드 표시) → 환불 요청 → 운영자 환불 → 「환불됨」.
 * 진짜 PostgreSQL · 보안 체인 · 결제 장부이고 결제사만 가짜다.
 */
class CheerJourneyIntegrationTest : CheerIntegrationBase() {
    @Test
    fun `the amounts come from the server's list, only for a signed-in account`() {
        get("/api/v1/cheers/options", null).andExpect { status { isUnauthorized() } }
        val body = get("/api/v1/cheers/options", user).andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertEquals(listOf("cheer-1000", "cheer-3000", "cheer-5000"), JsonPath.read<List<String>>(body, "$.values[*].itemRef"))
        assertEquals(listOf(1000, 3000, 5000), JsonPath.read<List<Int>>(body, "$.values[*].amount.amount"))
        assertEquals(listOf("KRW", "KRW", "KRW"), JsonPath.read<List<String>>(body, "$.values[*].amount.currency"))
    }

    @Test
    fun `a cheer is paid and recorded, and my history shows it with the card`() {
        val orderId = createOrder(user, "cheer-3000")
        assertEquals("[]", JsonPath.read<Any>(myCheers(user), "$.values").toString(), "nothing is recorded before the payment")

        confirm(user, orderId, 3000).andExpect { status { isOk() }; jsonPath("$.value.status") { value("PAID") } }

        val mine = myCheers(user)
        assertEquals(listOf(orderId), JsonPath.read<List<String>>(mine, "$.values[*].orderId"))
        assertEquals("PAID", JsonPath.read<String>(mine, "$.values[0].status"))
        assertEquals(3000, JsonPath.read<Int>(mine, "$.values[0].amount.amount"))
        assertEquals("KRW", JsonPath.read<String>(mine, "$.values[0].amount.currency"))
        assertEquals(0, JsonPath.read<Int>(mine, "$.values[0].refundedAmount"))
        assertEquals("HYUNDAI", JsonPath.read<String>(mine, "$.values[0].card.issuer"))
        assertEquals("1234", JsonPath.read<String>(mine, "$.values[0].card.last4"))
        assertTrue(JsonPath.read<String>(mine, "$.values[0].createdAt").isNotBlank())
        assertEquals(null, JsonPath.read<String?>(mine, "$.values[0].refundedAt"))
        assertEquals(false, JsonPath.read<Boolean>(mine, "$.cursor.hasNext"))
        // one line per order, even if the same payment is confirmed again
        confirm(user, orderId, 3000, paymentKey = jdbc.sql("select provider_payment_id from payment_orders where id = :i").param("i", orderId).query(String::class.java).single())
            .andExpect { status { isOk() } }
        assertEquals(1L, jdbc.sql("select count(*) from cheers where order_id = :i").param("i", orderId).query(Long::class.java).single())
    }

    @Test
    fun `a refund is requested by the buyer, run by the operator, and the cheer becomes refunded`() {
        val orderId = buy(user)
        val requested = post("$PAYMENTS/orders/$orderId/refund-requests", user, """{"reasonCode":"CUSTOMER_REQUEST"}""", UUID.randomUUID().toString())
            .andExpect { status { isCreated() }; jsonPath("$.value.status") { value("REQUESTED") } }
        val refundId = read(requested, "$.value.id")
        assertEquals("PAID", JsonPath.read<String>(myCheers(user), "$.values[0].status"), "a request moves no money")

        post("$ADMIN/refunds/$refundId/approve", operator).andExpect { status { isOk() }; jsonPath("$.value.status") { value("SUCCEEDED") } }

        val mine = await { myCheers(user).takeIf { JsonPath.read<String>(it, "$.values[0].status") == "REFUNDED" } }
        assertEquals(3000, JsonPath.read<Int>(mine, "$.values[0].refundedAmount"))
        assertTrue(JsonPath.read<String?>(mine, "$.values[0].refundedAt") != null)
        assertEquals(1, gateway.refundCalls.size)
    }


    @Test
    fun `an item that is not on the server's list, another quantity or another kind is ITEM_NOT_PURCHASABLE and no order is made`() {
        listOf(
            """{"itemType":"cheer","itemRef":"cheer-2000","quantity":1}""",
            """{"itemType":"cheer","itemRef":"cheer-3000","quantity":2}""",
            """{"itemType":"cheer","itemRef":"cheer-3000","quantity":0}""",
            """{"itemType":"plan","itemRef":"cheer-3000","quantity":1}""",
            """{"itemType":"cheer","itemRef":"cheer-0","quantity":1}""",
        ).forEach { body ->
            post("$PAYMENTS/orders", user, body, UUID.randomUUID().toString())
                .andExpect { status { isUnprocessableEntity() }; jsonPath("$.code") { value("PAYMENT.ITEM_NOT_PURCHASABLE") } }
        }
        assertEquals(0L, jdbc.sql("select count(*) from payment_orders").query(Long::class.java).single())
    }

    @Test
    fun `the amount is the server's - a different amount on confirm is refused before the provider is called`() {
        val orderId = createOrder(user, "cheer-1000")
        confirm(user, orderId, 100).andExpect { status { isBadRequest() }; jsonPath("$.code") { value("PAYMENT.AMOUNT_MISMATCH") } }
        assertEquals(0, gateway.confirmCalls.size)
        assertEquals("PENDING", jdbc.sql("select status from payment_orders where id = :i").param("i", orderId).query(String::class.java).single())
    }

    @Test
    fun `an approval the provider never answered is settled by the job, and then the cheer exists`() {
        gateway.script(ConfirmScript.TimeoutApplied())
        val orderId = createOrder(user)
        confirm(user, orderId, 3000).andExpect { status { isAccepted() }; jsonPath("$.value.status") { value("CONFIRMING") } }
        assertEquals("[]", JsonPath.read<Any>(myCheers(user), "$.values").toString(), "unknown is not paid")

        val mine = await { myCheers(user).takeIf { JsonPath.read<List<String>>(it, "$.values[*].orderId").isNotEmpty() } }
        assertEquals(orderId, JsonPath.read<String>(mine, "$.values[0].orderId"))
        assertEquals("PAID", JsonPath.read<String>(mine, "$.values[0].status"))
        assertEquals("PAID", jdbc.sql("select status from payment_orders where id = :i").param("i", orderId).query(String::class.java).single())
    }

    @Test
    fun `my history is newest first in cursor pages, a missing ledger order only costs the card`() {
        val account = accountOf("user@example.com")
        (1..3).forEach { n ->
            jdbc.sql("insert into cheers (order_id, account_id, amount, currency, refunded_amount, status, created_at) values (:o, :a, 1000, 'KRW', 0, 'PAID', now() - make_interval(mins => :m))")
                .param("o", "ord_old$n").param("a", account).param("m", n).update()
        }
        val first = get("/api/v1/cheers/me?size=2", user).andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertEquals(listOf("ord_old1", "ord_old2"), JsonPath.read<List<String>>(first, "$.values[*].orderId"))
        assertTrue(JsonPath.read<Boolean>(first, "$.cursor.hasNext"))
        assertEquals(null, JsonPath.read<Any?>(first, "$.values[0].card"), "no ledger order -> no card")
        val next = JsonPath.read<String>(first, "$.cursor.nextCursor")
        val second = get("/api/v1/cheers/me?size=2&cursor=$next", user).andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertEquals(listOf("ord_old3"), JsonPath.read<List<String>>(second, "$.values[*].orderId"))
        assertFalse(JsonPath.read<Boolean>(second, "$.cursor.hasNext"))
        get("/api/v1/cheers/me?cursor=not-a-cursor", user).andExpect { status { isBadRequest() } }
        assertEquals("[]", JsonPath.read<Any>(myCheers(stranger), "$.values").toString(), "another account sees none of mine")
    }

    @Test
    fun `a hand-made cursor with a time outside any real row is a bad cursor (400), never a 500`() {
        fun cursorOf(micros: String) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("$micros.ord_x".toByteArray())
        listOf("-9000000000000000000", "9000000000000000000", "-1", "253402300800000000").forEach { micros ->
            get("/api/v1/cheers/me?cursor=${cursorOf(micros)}", user).andExpect { status { isBadRequest() } }
        }
        // the edge a real cursor can have is fine
        get("/api/v1/cheers/me?cursor=${cursorOf("0")}", user).andExpect { status { isOk() } }
        get("/api/v1/cheers/me?cursor=${cursorOf("253402300799999999")}", user).andExpect { status { isOk() } }
    }
}
