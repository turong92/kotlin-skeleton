package dev.sumin.skeleton.payment.web

import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentOrderStatus
import dev.sumin.skeleton.payment.PaymentRefund
import dev.sumin.skeleton.payment.PaymentRefundStatus
import dev.sumin.skeleton.payment.testing.InMemoryPaymentLedgerStore
import dev.sumin.skeleton.payment.testing.PaymentTestOrders
import dev.sumin.skeleton.payment.testing.ScriptedGateway
import dev.sumin.skeleton.payment.web.PaymentHttpSupport.asUser
import dev.sumin.skeleton.payment.web.PaymentHttpSupport.keys
import dev.sumin.skeleton.payment.web.PaymentHttpSupport.read
import dev.sumin.skeleton.paymenttest.InMemoryLedgerBeans
import dev.sumin.skeleton.paymenttest.PaymentWebTestApplication
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * 사용자 경로(스펙 §7 · 계약 문서): 경로 · 상태 코드 · 오류 코드, 남의 것 = 없는 것, 사용자 응답에 운영자 칸이 없다.
 * 진짜 자동설정 순서로 조립한 메모리 장부 위에서 돈다 — 컨트롤러가 서비스를 **바깥 트랜잭션 없이** 부르는지는 서비스 입구의 검사가 증명한다.
 */
@SpringBootTest(
    classes = [PaymentWebTestApplication::class],
    // 한 컨텍스트에서 주문을 수십 번 만든다 — 한도 시험은 PaymentOrderRateLimitHttpTest 가 따로 한다
    properties = ["skeleton.payment.http.order-rate-limit.account-capacity=100000", "skeleton.payment.http.order-rate-limit.ip-capacity=100000"],
)
@AutoConfigureMockMvc
@Import(InMemoryLedgerBeans::class)
class PaymentUserHttpTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var store: InMemoryPaymentLedgerStore
    @Autowired lateinit var gateway: ScriptedGateway

    private val base = "/api/v1/payments"
    private var sequence = 0

    private fun account() = "acc_http_${System.nanoTime()}_${sequence++}"

    private fun createOrder(account: String, key: String = "k-${System.nanoTime()}", itemRef: String = "cheer-3000", quantity: Int? = null): ResultActions =
        mvc.perform(
            post("$base/orders").asUser(account).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("""{"itemType":"cheer","itemRef":"$itemRef"${quantity?.let { ""","quantity":$it""" } ?: ""}}"""),
        )

    private fun newOrder(account: String): String =
        read(createOrder(account).andExpect(status().isCreated).andReturn().response.contentAsString)["value"]["order"]["id"].asString()

    private fun confirm(account: String, orderId: String, paymentKey: String, amount: String = "3000"): ResultActions =
        mvc.perform(post("$base/orders/$orderId/confirm").asUser(account).contentType(MediaType.APPLICATION_JSON).content("""{"providerPaymentId":"$paymentKey","amount":$amount}"""))

    private fun paidOrder(account: String): String {
        val id = newOrder(account)
        val key = "pay_$id"
        gateway.authenticate(key, id, PaymentAmount(3000, "KRW"))
        confirm(account, id, key).andExpect(status().isOk).andExpect(jsonPath("$.value.status").value("PAID"))
        return id
    }

    private fun refundRequest(account: String, orderId: String, key: String = "r-${System.nanoTime()}", body: String = """{"reasonCode":"CUSTOMER_REQUEST"}"""): ResultActions =
        mvc.perform(post("$base/orders/$orderId/refund-requests").asUser(account).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))

    // --- 주문 만들기 ---

    @Test
    fun `creating an order is 201 with the order and the values for the checkout window, and the same key again is 200 with the same order`() {
        val account = account()
        val first = createOrder(account, "same-key").andExpect(status().isCreated)
            .andExpect(jsonPath("$.value.order.status").value("PENDING"))
            .andExpect(jsonPath("$.value.order.amount.amount").value(3000))
            .andExpect(jsonPath("$.value.checkout.kind").value("SCRIPTED_WINDOW"))
            .andReturn().response.contentAsString

        val again = createOrder(account, "same-key").andExpect(status().isOk).andReturn().response.contentAsString

        assertEquals(read(first)["value"]["order"]["id"], read(again)["value"]["order"]["id"])
    }

    @Test
    fun `an order without an Idempotency-Key header is 400 validation failed`() {
        mvc.perform(post("$base/orders").asUser(account()).contentType(MediaType.APPLICATION_JSON).content("""{"itemType":"cheer","itemRef":"cheer-3000"}"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("COMMON.VALIDATION_FAILED"))
    }

    @Test
    fun `the same key with another body is 409 idempotency conflict, and an item the catalog refuses is 422`() {
        val account = account()
        createOrder(account, "k-conflict").andExpect(status().isCreated)

        createOrder(account, "k-conflict", itemRef = "cheer-5000").andExpect(status().isConflict).andExpect(jsonPath("$.code").value("PAYMENT.IDEMPOTENCY_CONFLICT"))
        createOrder(account, itemRef = "cheer-999999").andExpect(status().`is`(422)).andExpect(jsonPath("$.code").value("PAYMENT.ITEM_NOT_PURCHASABLE"))
        createOrder(account, quantity = 2).andExpect(status().`is`(422)).andExpect(jsonPath("$.code").value("PAYMENT.ITEM_NOT_PURCHASABLE"))
    }

    @Test
    fun `config says whether payments can be taken, and when it says not available creating an order is 503 UNAVAILABLE and makes no order`() {
        val account = account()
        mvc.perform(get("$base/config").asUser(account)).andExpect(jsonPath("$.value.available").value(true)).andExpect(jsonPath("$.value.testMode").value(true))
        val orders = store.allOrders().size
        gateway.checkoutReady = false
        try {
            mvc.perform(get("$base/config").asUser(account)).andExpect(jsonPath("$.value.available").value(false))
            createOrder(account, "k-unavail").andExpect(status().isServiceUnavailable).andExpect(jsonPath("$.code").value("PAYMENT.UNAVAILABLE"))
        } finally {
            gateway.checkoutReady = true
        }
        assertEquals(orders, store.allOrders().size)
    }

    // --- 승인 ---

    @Test
    fun `confirm maps the outcomes - paid 200 with the card, unknown answer 202 CONFIRMING, changed amount 400 before the provider, refused card 402 with a category`() {
        val account = account()
        val paid = newOrder(account)
        gateway.authenticate("pay_$paid", paid, PaymentAmount(3000, "KRW"))
        confirm(account, paid, "pay_$paid").andExpect(status().isOk)
            .andExpect(jsonPath("$.value.status").value("PAID"))
            .andExpect(jsonPath("$.value.card.issuer").value("HYUNDAI"))
            .andExpect(jsonPath("$.value.card.last4").value("1234"))

        val unknown = newOrder(account)
        gateway.authenticate("pay_$unknown", unknown, PaymentAmount(3000, "KRW"))
        gateway.script(ScriptedGateway.ConfirmScript.TimeoutNotApplied)
        confirm(account, unknown, "pay_$unknown").andExpect(status().isAccepted).andExpect(jsonPath("$.value.status").value("CONFIRMING"))

        val changed = newOrder(account)
        gateway.authenticate("pay_$changed", changed, PaymentAmount(3000, "KRW"))
        val calls = gateway.confirmCalls.size
        confirm(account, changed, "pay_$changed", amount = "2999").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("PAYMENT.AMOUNT_MISMATCH"))
        assertEquals(calls, gateway.confirmCalls.size)

        val declined = newOrder(account)
        gateway.authenticate("pay_$declined", declined, PaymentAmount(3000, "KRW"))
        gateway.script(ScriptedGateway.ConfirmScript.Decline("REJECT_CARD_COMPANY"))
        confirm(account, declined, "pay_$declined").andExpect(status().isPaymentRequired)
            .andExpect(jsonPath("$.code").value("PAYMENT.DECLINED"))
            .andExpect(jsonPath("$.data.providerCode").value("REJECT_CARD_COMPANY"))
            .andExpect(jsonPath("$.data.category").value("CARD_DECLINED"))
    }

    @Test
    fun `a confirm body whose amount is not a whole number or whose payment key is empty is 400`() {
        val account = account()
        val id = newOrder(account)

        confirm(account, id, "pay_x", amount = "\"3000\"").andExpect(status().isBadRequest)
        confirm(account, id, "", amount = "3000").andExpect(status().isBadRequest)
        confirm(account, id, "pay_x", amount = "30.5").andExpect(status().isBadRequest)
    }

    @Test
    fun `someone else's order and a missing order are the same 404 on every path`() {
        val owner = account()
        val other = account()
        val id = newOrder(owner)
        val body = """{"providerPaymentId":"pay_x","amount":3000}"""
        val missing = "ord_${"0".repeat(32)}"

        listOf(id, missing).forEach { target ->
            mvc.perform(get("$base/orders/$target").asUser(other)).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("PAYMENT.ORDER_NOT_FOUND"))
            mvc.perform(post("$base/orders/$target/confirm").asUser(other).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("PAYMENT.ORDER_NOT_FOUND"))
            mvc.perform(post("$base/orders/$target/abandon").asUser(other)).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("PAYMENT.ORDER_NOT_FOUND"))
            refundRequest(other, target).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("PAYMENT.ORDER_NOT_FOUND"))
        }
        assertEquals(PaymentOrderStatus.PENDING, store.findOrder(id)!!.status, "the other caller changed nothing")
    }

    // --- 그만둠 · 읽기 ---

    @Test
    fun `one order comes with its refund lines and the summary fields - and a pending attempt is readable by its owner`() {
        val account = account()
        val paid = paidOrder(account)
        refundRequest(account, paid).andExpect(status().isCreated)
        val pending = newOrder(account)

        mvc.perform(get("$base/orders/$paid").asUser(account)).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.order.status").value("PAID"))
            .andExpect(jsonPath("$.value.order.refundableAmount").value(3000))
            .andExpect(jsonPath("$.value.order.openRefundStatus").value("REQUESTED"))
            .andExpect(jsonPath("$.value.refunds.length()").value(1))
            .andExpect(jsonPath("$.value.refunds[0].status").value("REQUESTED"))
            .andExpect(jsonPath("$.value.refunds[0].origin").value("USER"))
        mvc.perform(get("$base/orders/$pending").asUser(account)).andExpect(status().isOk).andExpect(jsonPath("$.value.order.status").value("PENDING"))
    }

    @Test
    fun `my payments come newest first as a cursor page without the attempts that were never paid`() {
        val account = account()
        val ids = (1..3).map { paidOrder(account) }
        newOrder(account) // 결제하지 않은 시도는 빠진다

        val first = read(mvc.perform(get("$base/orders?size=2").asUser(account)).andExpect(status().isOk).andReturn().response.contentAsString)
        assertEquals(2, first["values"].size())
        assertTrue(first["cursor"]["hasNext"].asBoolean())
        val second = read(mvc.perform(get("$base/orders?size=2&cursor=${first["cursor"]["nextCursor"].asString()}").asUser(account)).andExpect(status().isOk).andReturn().response.contentAsString)

        val listed = (first["values"] + second["values"]).map { it["id"].asString() }
        assertEquals(3, listed.size)
        assertEquals(ids.toSet(), listed.toSet())
        assertTrue(!second["cursor"]["hasNext"].asBoolean())
        assertEquals(listOf(PaymentOrderStatus.PAID.name), (first["values"] + second["values"]).map { it["status"].asString() }.distinct())
    }

    @Test
    fun `a cursor whose time or id is outside what the databases hold, or that is not ours, is 400 - not a 500 from the query`() {
        fun cursor(text: String) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())
        listOf("o.-1.ord_x", "o.9223372036854775807.ord_x", "o.300000000000000000.ord_x", "o.1700000000000000.ord x", "o.1700000000000000.ord\u0000x").forEach { raw ->
            mvc.perform(get("$base/orders?cursor=${cursor(raw)}").asUser(account())).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("COMMON.VALIDATION_FAILED"))
        }
        mvc.perform(get("$base/orders?cursor=${cursor("o.1700000000000000.ord_x")}").asUser(account())).andExpect(status().isOk)
        mvc.perform(get("$base/orders?cursor=not-a-cursor").asUser(account())).andExpect(status().isBadRequest)
    }

    // --- 환불 요청 ---

    @Test
    fun `a refund request is 201 and only a request - the money does not move - and the same key again is 200 with the same line`() {
        val account = account()
        val paid = paidOrder(account)

        val first = refundRequest(account, paid, "r-same", """{"reasonCode":"DUPLICATE","amount":1000}""").andExpect(status().isCreated)
            .andExpect(jsonPath("$.value.status").value("REQUESTED"))
            .andExpect(jsonPath("$.value.origin").value("USER"))
            .andExpect(jsonPath("$.value.amount.amount").value(1000))
            .andExpect(jsonPath("$.value.reasonCode").value("DUPLICATE"))
            .andReturn().response.contentAsString
        val again = refundRequest(account, paid, "r-same", """{"reasonCode":"DUPLICATE","amount":1000}""").andExpect(status().isOk).andReturn().response.contentAsString

        assertEquals(read(first)["value"]["id"], read(again)["value"]["id"])
        assertEquals(emptyList(), gateway.refundCalls.filter { it.providerPaymentId == "pay_$paid" }, "asking is not refunding")
    }

    @Test
    fun `the refund request errors - a reason a customer may not pick is 400, too much is 400, a second open one is 409, an unpaid order is 409`() {
        val account = account()
        val paid = paidOrder(account)

        refundRequest(account, paid, body = """{"reasonCode":"OPERATOR"}""").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("COMMON.VALIDATION_FAILED"))
        refundRequest(account, paid, body = """{"reasonCode":"free text"}""").andExpect(status().isBadRequest)
        refundRequest(account, paid, body = """{"reasonCode":"CUSTOMER_REQUEST","amount":3001}""").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("PAYMENT.REFUND_AMOUNT_EXCEEDED"))
        refundRequest(account, paid).andExpect(status().isCreated)
        refundRequest(account, paid).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("PAYMENT.REFUND_IN_PROGRESS"))
        refundRequest(account, newOrder(account)).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("PAYMENT.REFUND_NOT_ALLOWED"))
        mvc.perform(post("$base/orders/$paid/refund-requests").asUser(account).contentType(MediaType.APPLICATION_JSON).content("""{"reasonCode":"CUSTOMER_REQUEST"}"""))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `withdrawing my own request gives the line back as withdrawn by me, a second time is 409, and someone else's line is 404`() {
        val account = account()
        val paid = paidOrder(account)
        val line = read(refundRequest(account, paid).andReturn().response.contentAsString)["value"]["id"].asString()

        mvc.perform(post("$base/refunds/$line/withdraw").asUser(account())).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("PAYMENT.REFUND_NOT_FOUND"))
        mvc.perform(post("$base/refunds/rfd_${"0".repeat(32)}/withdraw").asUser(account)).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("PAYMENT.REFUND_NOT_FOUND"))
        mvc.perform(post("$base/refunds/$line/withdraw").asUser(account)).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.status").value("WITHDRAWN"))
            .andExpect(jsonPath("$.value.closedBy").value("USER"))
        mvc.perform(post("$base/refunds/$line/withdraw").asUser(account)).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("PAYMENT.REFUND_NOT_ALLOWED"))
    }

    @Test
    fun `a request the system closed because the order was refunded in full says so with closedBy SYSTEM - and shows no failure code`() {
        val account = account()
        val paid = paidOrder(account)
        val line = PaymentTestOrders.refund(
            id = "rfd_" + "9".repeat(32), orderId = paid, status = PaymentRefundStatus.WITHDRAWN, now = Instant.now(),
        ).copy(failureCode = "ORDER_REFUNDED", requestedBy = account, decidedBy = null)
        insertRefund(paid, line)

        val body = mvc.perform(get("$base/orders/$paid").asUser(account)).andExpect(status().isOk).andReturn().response.contentAsString

        val refund = read(body)["value"]["refunds"].single { it["id"].asString() == line.id }
        assertEquals("SYSTEM", refund["closedBy"].asString())
        assertTrue(!refund.has("failureCode"))
    }

    // --- 사용자 응답에 나가면 안 되는 것 ---

    @Test
    fun `no user response carries a provider payment number, a review reason, an operator id, a failure code, an internal key or an inbox field`() {
        val account = account()
        val paid = paidOrder(account)
        store.inTransaction { tx ->
            val locked = tx.lockOrder(paid)!!
            tx.updateOrder(locked.copy(reviewReason = dev.sumin.skeleton.payment.PaymentReviewReason.AMOUNT_MISMATCH, failureCode = null))
        }
        val line = read(refundRequest(account, paid).andReturn().response.contentAsString)["value"]["id"].asString()
        val bodies = listOf(
            mvc.perform(get("$base/orders/$paid").asUser(account)).andReturn().response.contentAsString,
            mvc.perform(get("$base/orders").asUser(account)).andReturn().response.contentAsString,
            mvc.perform(post("$base/refunds/$line/withdraw").asUser(account)).andReturn().response.contentAsString,
            confirm(account, paid, "pay_$paid").andReturn().response.contentAsString,
            mvc.perform(post("$base/orders/$paid/abandon").asUser(account)).andReturn().response.contentAsString,
        ).map(::read)

        val forbidden = setOf(
            "providerPaymentId", "provider", "providerMode", "reviewReason", "requestedBy", "decidedBy", "providerRefundId", "expectedRemaining",
            "dedupeKey", "openKey", "payload", "payloadHash", "eventKey", "accountId", "accountErasedAt", "taxAmount", "paidAmount", "events", "confirmStartedAt",
        )
        bodies.forEach { body -> assertEquals(emptySet(), keys(body) intersect forbidden, "user body leaks: $body") }
        bodies.forEach { body -> assertTrue("pay_$paid" !in body.toString(), "the provider payment number is not in a user response") }
        val allowedRefundKeys = setOf("id", "orderId", "status", "amount", "currency", "reasonCode", "origin", "requestedAt", "completedAt", "closedBy")
        val refundKeys = keys(bodies[0]["value"]["refunds"])
        assertEquals(emptySet(), refundKeys - allowedRefundKeys, "a refund line in a user response has only the neutral fields")
    }

    private fun insertRefund(orderId: String, line: PaymentRefund) {
        store.inTransaction { tx ->
            tx.lockOrder(orderId)
            tx.insertRefundIfAbsent(line)
        }
    }
}
