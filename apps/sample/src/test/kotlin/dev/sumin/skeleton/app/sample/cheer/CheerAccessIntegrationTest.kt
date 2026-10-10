package dev.sumin.skeleton.app.sample.cheer

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.payment.PaymentCard
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put

/**
 * 누가 무엇을 할 수 있나(스펙 §13.2): 남의 주문은 없는 주문(404), 본인은 환불을 실행할 수 없고(운영자 경로 403), 운영자 판정은 토큰이 아니라 **저장소의 지금 역할**이며,
 * 웹훅 경로는 진짜 보안 체인에서 익명으로 닿되 POST 두 모양뿐이고, 사용자 응답에는 운영자 칸도 심은 값도 없다.
 */
class CheerAccessIntegrationTest : CheerIntegrationBase() {
    @Test
    fun `someone else's order is a missing order - read, confirm, abandon and refund request`() {
        val orderId = createOrder(user)
        get("$PAYMENTS/orders/$orderId", stranger).andExpect { status { isNotFound() }; jsonPath("$.code") { value("PAYMENT.ORDER_NOT_FOUND") } }
        post("$PAYMENTS/orders/$orderId/abandon", stranger).andExpect { status { isNotFound() } }
        gateway.authenticate("pay_other", orderId, dev.sumin.skeleton.payment.PaymentAmount(3000, "KRW"))
        post("$PAYMENTS/orders/$orderId/confirm", stranger, """{"providerPaymentId":"pay_other","amount":3000}""").andExpect { status { isNotFound() } }
        assertEquals(0, gateway.confirmCalls.size, "the provider is not called for someone else's order")
        val paid = buy(user)
        post("$PAYMENTS/orders/$paid/refund-requests", stranger, """{"reasonCode":"CUSTOMER_REQUEST"}""", UUID.randomUUID().toString()).andExpect { status { isNotFound() } }
        val list = get("$PAYMENTS/orders", stranger).andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertEquals("[]", JsonPath.read<Any>(list, "$.values").toString())
        get("$PAYMENTS/orders/$paid", user).andExpect { status { isOk() } }   // the owner still sees it
    }


    @Test
    fun `the operator is decided by the account store on every call - a token that still says ADMIN is not enough after the role is taken away`() {
        val admin = accountOf("admin@example.com")
        val orderId = buy(user)
        get("$ADMIN/orders", operator).andExpect { status { isOk() } }
        jdbc.sql("delete from account_roles where account_id = :a and role = 'ADMIN'").param("a", admin).update()
        try {
            get("$ADMIN/orders", operator).andExpect { status { isForbidden() } }
            // the demoted administrator's refund POST is refused as well, and the provider is never called
            post("$ADMIN/orders/$orderId/refunds", operator, """{"reasonCode":"OPERATOR"}""", UUID.randomUUID().toString()).andExpect { status { isForbidden() } }
            assertEquals(0, gateway.refundCalls.size, "the demoted administrator reached the provider")
        } finally {
            jdbc.sql("insert into account_roles (account_id, role) values (:a, 'ADMIN')").param("a", admin).update()
        }
        get("$ADMIN/orders", operator).andExpect { status { isOk() } }
    }

    @Test
    fun `the webhook path is reached anonymously through the real security chain - two POST shapes only`() {
        val orderId = buy(user)
        val key = jdbc.sql("select provider_payment_id from payment_orders where id = :i").param("i", orderId).query(String::class.java).single()
        val delivery = gateway.webhookDelivery(eventKey = "evt-anon-1", paymentKey = key, orderId = orderId)
        fun hook(path: String) = mvc.post(path) {
            contentType = MediaType.APPLICATION_JSON
            content = delivery.body
            delivery.headers.forEach { (name, value) -> header(name, value) }
        }

        // the two public shapes: no authentication, and the controller (not the chain) answers
        hook("$PAYMENTS/webhooks/scripted/$WEBHOOK_TOKEN").andExpect { status { isOk() } }
        hook("$PAYMENTS/webhooks/scripted/wrong-token-wrong-token-wrong-token").andExpect { status { isNotFound() } }   // a wrong secret piece looks like no path at all
        hook("$PAYMENTS/webhooks/scripted").andExpect { status { isNotFound() } }                                         // the token-less shape reaches the controller, which knows this provider is unsigned
        assertEquals(1L, jdbc.sql("select count(*) from payment_webhook_events").query(Long::class.java).single(), "only the delivery with the right secret piece was kept")

        // everything else near it is the security chain's 401
        mvc.get("$PAYMENTS/webhooks/scripted/$WEBHOOK_TOKEN").andExpect { status { isUnauthorized() } }
        mvc.get("$PAYMENTS/webhooks/scripted").andExpect { status { isUnauthorized() } }
        hook("$PAYMENTS/webhooks/scripted/$WEBHOOK_TOKEN/extra").andExpect { status { isUnauthorized() } }
        hook("$PAYMENTS/webhooks/scripted/$WEBHOOK_TOKEN/extra/more").andExpect { status { isUnauthorized() } }
        hook("$PAYMENTS/webhooks").andExpect { status { isUnauthorized() } }
        hook("$PAYMENTS/webhookz/scripted/$WEBHOOK_TOKEN").andExpect { status { isUnauthorized() } }
        hook("$PAYMENTS/orders").andExpect { status { isUnauthorized() } }
        mvc.put("$PAYMENTS/webhooks/scripted/$WEBHOOK_TOKEN") { content = delivery.body }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `no user response carries an operator-only field or a planted value - and the operator response does carry them`() {
        val planted = listOf("4111111111111111", "Kim Minsu", "kim@example.com", "010-1234-5678")
        gateway.confirmCarriesDetails = true
        val orderId = createOrder(user, "cheer-3000")
        // the provider leaks a full number and a name into the card fields: the ledger drops what is not an issuer code / four characters
        val confirmed = confirm(user, orderId, 3000, card = PaymentCard("Kim Minsu card", "4111111111111111")).andExpect { status { isOk() } }
        val paymentKey = jdbc.sql("select provider_payment_id from payment_orders where id = :i").param("i", orderId).query(String::class.java).single()
        // a webhook body with personal data is not stored either
        val delivery = gateway.webhookDelivery(eventKey = "evt-plant-1", paymentKey = paymentKey, orderId = orderId, plant = planted.joinToString(" "))
        mvc.post("$PAYMENTS/webhooks/scripted/$WEBHOOK_TOKEN") { contentType = MediaType.APPLICATION_JSON; content = delivery.body; delivery.headers.forEach { (n, v) -> header(n, v) } }.andExpect { status { isOk() } }

        val refundRequest = post("$PAYMENTS/orders/$orderId/refund-requests", user, """{"reasonCode":"CUSTOMER_REQUEST","amount":1000}""", UUID.randomUUID().toString()).andExpect { status { isCreated() } }
        val refundId = read(refundRequest, "$.value.id")
        // the two answers a buyer gets before paying: the checkout (order creation) and the abandon of an unpaid order
        val checkout = post("$PAYMENTS/orders", user, """{"itemType":"cheer","itemRef":"cheer-5000","quantity":1}""", UUID.randomUUID().toString()).andExpect { status { isCreated() } }
        val unpaidId = read(checkout, "$.value.order.id")
        val abandoned = post("$PAYMENTS/orders/$unpaidId/abandon", user).andExpect { status { isOk() } }
        val responses = listOf(
            confirmed.andReturn().response.contentAsString,
            checkout.andReturn().response.contentAsString,
            abandoned.andReturn().response.contentAsString,
            refundRequest.andReturn().response.contentAsString,
            get("$PAYMENTS/orders/$orderId", user).andExpect { status { isOk() } }.andReturn().response.contentAsString,
            get("$PAYMENTS/orders", user).andExpect { status { isOk() } }.andReturn().response.contentAsString,
            get("$PAYMENTS/config", user).andExpect { status { isOk() } }.andReturn().response.contentAsString,
            myCheers(user),
            post("$PAYMENTS/refunds/$refundId/withdraw", user).andExpect { status { isOk() } }.andReturn().response.contentAsString,
            get("/api/v1/cheers/options", user).andReturn().response.contentAsString,
        )
        val operatorOnly = listOf("providerPaymentId", "accountId", "reviewReason", "provider", "decidedBy", "requestedBy", "actorId", "dedupeKey", "failureCode")
        fun keysOf(body: String) = Regex("\"([A-Za-z]+)\"\\s*:").findAll(body).map { m -> m.groupValues[1] }.toSet()
        val checkoutKeys = keysOf(responses[1])   // the checkout names the provider for the payment window - that is its job (contract: the window instruction), not an operator field
        assertTrue("provider" in checkoutKeys && "clientKey" in checkoutKeys, "the checkout answer is the window instruction: $checkoutKeys")
        val userKeys = responses.filterIndexed { i, _ -> i != 1 }.flatMap { keysOf(it) }.toSet()
        assertTrue(userKeys.isNotEmpty())
        // failureCode is a user-visible field of an order (null here) - the others never are
        (operatorOnly - "failureCode").forEach { assertFalse(it in userKeys, "$it is an operator-only field but a user response has it") }
        (operatorOnly - "failureCode" - "provider").forEach { assertFalse(it in checkoutKeys, "$it is an operator-only field but the checkout answer has it") }
        responses.forEach { body ->
            (planted + paymentKey + listOf("user@example.com", accountOf("user@example.com"))).forEach { secret ->
                assertFalse(body.contains(secret), "a user response carries $secret")
            }
        }
        // the card the user sees is the clean part only (nothing was kept of the leaked number)
        assertEquals(null, JsonPath.read<Any?>(myCheers(user), "$.values[0].card"), "a leaked issuer / number is dropped, not shown")

        // the operator's own view does carry the provider side - so the check above is not vacuous
        val adminView = get("$ADMIN/orders/$orderId", operator).andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertTrue(adminView.contains(paymentKey) && adminView.contains(accountOf("user@example.com")), "the operator sees the payment key and the account id")
        planted.forEach { assertFalse(adminView.contains(it), "even the operator view never holds $it") }
        val tables = listOf("payment_orders", "payment_refunds", "payment_events", "payment_webhook_events", "jobs", "cheers")
        tables.forEach { table ->
            val rows = jdbc.sql("select cast(t as text) from $table t").query(String::class.java).list().filterNotNull()
            planted.forEach { secret -> assertTrue(rows.none { it.contains(secret, ignoreCase = true) }, "$table holds the planted $secret") }
        }
    }

}
