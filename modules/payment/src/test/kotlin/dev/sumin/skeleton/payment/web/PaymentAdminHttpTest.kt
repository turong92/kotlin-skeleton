package dev.sumin.skeleton.payment.web

import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentOrderStatus
import dev.sumin.skeleton.payment.PaymentRefundStatus
import dev.sumin.skeleton.payment.PaymentReviewReason
import dev.sumin.skeleton.payment.testing.InMemoryPaymentLedgerStore
import dev.sumin.skeleton.payment.testing.PaymentTestOrders
import dev.sumin.skeleton.payment.testing.ScriptedGateway
import dev.sumin.skeleton.payment.web.PaymentHttpSupport.asOperator
import dev.sumin.skeleton.payment.web.PaymentHttpSupport.asUser
import dev.sumin.skeleton.payment.web.PaymentHttpSupport.read
import dev.sumin.skeleton.paymenttest.InMemoryLedgerBeans
import dev.sumin.skeleton.paymenttest.PaymentWebTestApplication
import java.time.Duration
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

/** 운영자 경로(스펙 §7 · 계약 문서) — 권한(역할 없음 403), 목록 · 한 건, 환불 · 승인 · 거절 · 맞추기 · 수신함. 환불 · 승인 오류 코드는 운영자 계약 C1~C8 · 추가 ①~⑥. */
@SpringBootTest(
    classes = [PaymentWebTestApplication::class],
    properties = [
        "skeleton.payment.admin.enabled=true", "skeleton.payment.webhook.path-token=kit-webhook-token-0123456789abcdef",
        // 이 클래스는 한 컨텍스트에서 주문을 수십 번 만든다 — 한도 시험은 PaymentOrderRateLimitHttpTest 가 따로 한다
        "skeleton.payment.http.order-rate-limit.account-capacity=100000", "skeleton.payment.http.order-rate-limit.ip-capacity=100000",
    ],
)
@AutoConfigureMockMvc
@Import(InMemoryLedgerBeans::class)
class PaymentAdminHttpTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var store: InMemoryPaymentLedgerStore
    @Autowired lateinit var gateway: ScriptedGateway

    private val admin = "/api/v1/admin/payments"
    private val user = "/api/v1/payments"
    private var sequence = 0

    private fun account() = "acc_adm_${System.nanoTime()}_${sequence++}"

    private fun paid(account: String = account()): String {
        val id = read(
            mvc.perform(post("$user/orders").asUser(account).header("Idempotency-Key", "k-${System.nanoTime()}").contentType(MediaType.APPLICATION_JSON).content("""{"itemType":"cheer","itemRef":"cheer-3000"}"""))
                .andReturn().response.contentAsString,
        )["value"]["order"]["id"].asString()
        gateway.authenticate("pay_$id", id, PaymentAmount(3000, "KRW"))
        mvc.perform(post("$user/orders/$id/confirm").asUser(account).contentType(MediaType.APPLICATION_JSON).content("""{"providerPaymentId":"pay_$id","amount":3000}""")).andExpect(status().isOk)
        return id
    }

    private fun userRequest(account: String, orderId: String, amount: Long = 3000): String =
        read(
            mvc.perform(
                post("$user/orders/$orderId/refund-requests").asUser(account).header("Idempotency-Key", "r-${System.nanoTime()}")
                    .contentType(MediaType.APPLICATION_JSON).content("""{"reasonCode":"CUSTOMER_REQUEST","amount":$amount}"""),
            ).andExpect(status().isCreated).andReturn().response.contentAsString,
        )["value"]["id"].asString()

    private fun refundNow(orderId: String, key: String = "op-${System.nanoTime()}", body: String = """{"reasonCode":"OPERATOR","amount":3000}""", operator: String = "operator_1"): ResultActions =
        mvc.perform(post("$admin/orders/$orderId/refunds").asOperator(operator).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))

    // --- 권한 ---

    @Test
    fun `an operator path is 403 for a caller without the role and 401 for an anonymous one - a few paths by hand, every mapping is covered by PaymentHttpAccessTest`() {
        val id = paid()
        mvc.perform(get("$admin/orders").asUser("acc_plain", "USER")).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("COMMON.FORBIDDEN"))
        mvc.perform(get("$admin/orders/$id").asUser("acc_plain")).andExpect(status().isForbidden)
        mvc.perform(post("$admin/orders/$id/sync").asUser("acc_plain")).andExpect(status().isForbidden)
        mvc.perform(post("$admin/refunds/rfd_x/approve").asUser("acc_plain")).andExpect(status().isForbidden)
        mvc.perform(get("$admin/webhook-events").asUser("acc_plain")).andExpect(status().isForbidden)
        mvc.perform(get("$admin/orders")).andExpect(status().isUnauthorized)
    }

    // --- 목록 · 한 건 ---

    @Test
    fun `the order list carries the operator fields on every row, newest first, and filters by account and by status`() {
        val account = account()
        val first = paid(account)
        val second = paid(account)

        val body = read(mvc.perform(get("$admin/orders?accountId=$account").asOperator()).andExpect(status().isOk).andReturn().response.contentAsString)

        assertEquals(listOf(second, first), body["values"].toList().map { it["id"].asString() })
        val row = body["values"][0]
        assertEquals(account, row["accountId"].asString())
        assertEquals("scripted", row["provider"].asString())
        assertEquals("pay_$second", row["providerPaymentId"].asString())
        assertTrue(row.has("reviewReason") && row["reviewReason"].isNull)
        assertEquals("PAID", row["status"].asString())
        mvc.perform(get("$admin/orders?accountId=$account&status=REFUNDED").asOperator()).andExpect(status().isOk).andExpect(jsonPath("$.values.length()").value(0))
        mvc.perform(get("$admin/orders?status=NOPE").asOperator()).andExpect(status().isBadRequest)
    }

    @Test
    fun `the list pages by a cursor`() {
        val account = account()
        val ids = (1..3).map { paid(account) }

        val firstPage = read(mvc.perform(get("$admin/orders?accountId=$account&size=2").asOperator()).andReturn().response.contentAsString)
        val secondPage = read(mvc.perform(get("$admin/orders?accountId=$account&size=2&cursor=${firstPage["cursor"]["nextCursor"].asString()}").asOperator()).andReturn().response.contentAsString)

        assertEquals(ids.reversed(), (firstPage["values"] + secondPage["values"]).map { it["id"].asString() })
        assertTrue(firstPage["cursor"]["hasNext"].asBoolean())
        assertTrue(!secondPage["cursor"]["hasNext"].asBoolean())
    }

    @Test
    fun `q finds an order by its exact id or by the provider payment number and nothing else`() {
        val id = paid()

        mvc.perform(get("$admin/orders?q=$id").asOperator()).andExpect(status().isOk).andExpect(jsonPath("$.values.length()").value(1)).andExpect(jsonPath("$.values[0].id").value(id))
        mvc.perform(get("$admin/orders?q=pay_$id").asOperator()).andExpect(status().isOk).andExpect(jsonPath("$.values.length()").value(1)).andExpect(jsonPath("$.values[0].id").value(id))
        mvc.perform(get("$admin/orders?q=${id.take(10)}").asOperator()).andExpect(status().isOk).andExpect(jsonPath("$.values.length()").value(0))
    }

    @Test
    fun `the review list holds the rows with a review reason and the derived ones - a stale confirming order and a stale pending refund - and the derived reason is on the row`() {
        val account = account()
        val marked = paid(account)
        store.inTransaction { tx -> tx.updateOrder(tx.lockOrder(marked)!!.copy(reviewReason = PaymentReviewReason.AMOUNT_MISMATCH)) }
        val stale = paid(account)
        store.inTransaction { tx -> tx.updateOrder(tx.lockOrder(stale)!!.copy(status = PaymentOrderStatus.CONFIRMING, paidAt = null, confirmStartedAt = Instant.now().minus(Duration.ofHours(5)))) }
        val staleRefund = paid(account)
        store.inTransaction { tx ->
            tx.lockOrder(staleRefund)
            tx.insertRefundIfAbsent(
                PaymentTestOrders.refund(id = "rfd_" + "7".repeat(32), orderId = staleRefund, status = PaymentRefundStatus.PENDING, origin = dev.sumin.skeleton.payment.PaymentRefundOrigin.OPERATOR, now = Instant.now().minus(Duration.ofHours(6)))
                    .copy(sentAt = Instant.now().minus(Duration.ofHours(6))),
            )
        }
        val healthy = paid(account)
        store.inTransaction { tx -> tx.updateOrder(tx.lockOrder(healthy)!!.copy(fulfilledAt = Instant.now())) }

        val rows = read(mvc.perform(get("$admin/orders?accountId=$account&review=true").asOperator()).andExpect(status().isOk).andReturn().response.contentAsString)["values"]
        val reasons = rows.associate { it["id"].asString() to it["reviewReason"].asString() }

        assertEquals("AMOUNT_MISMATCH", reasons[marked])
        assertEquals("STALE_CONFIRMING", reasons[stale])
        assertEquals("STALE_REFUND", reasons[staleRefund])
        assertTrue(healthy !in reasons)
        // 한 건 상세에도 같은 파생 이유가 붙는다
        mvc.perform(get("$admin/orders/$stale").asOperator()).andExpect(jsonPath("$.value.order.reviewReason").value("STALE_CONFIRMING"))
        mvc.perform(get("$admin/orders/$staleRefund").asOperator()).andExpect(jsonPath("$.value.order.reviewReason").value("STALE_REFUND"))
    }

    @Test
    fun `a paid order nobody fulfilled and a succeeded refund nobody applied are in the review list once they are stale, with the reason their dead job would have set`() {
        val account = account()
        val unfulfilled = paid(account)
        store.inTransaction { tx -> tx.updateOrder(tx.lockOrder(unfulfilled)!!.copy(fulfilledAt = null, paidAt = Instant.now().minus(Duration.ofHours(3)))) }
        val unapplied = paid(account)
        store.inTransaction { tx ->
            tx.updateOrder(tx.lockOrder(unapplied)!!.copy(fulfilledAt = Instant.now()))
            tx.insertRefundIfAbsent(
                PaymentTestOrders.refund(id = "rfd_" + "8".repeat(32), orderId = unapplied, status = PaymentRefundStatus.SUCCEEDED, now = Instant.now().minus(Duration.ofHours(3)))
                    .copy(completedAt = Instant.now().minus(Duration.ofHours(3))),
            )
        }

        val reasons = read(mvc.perform(get("$admin/orders?accountId=$account&review=true").asOperator()).andReturn().response.contentAsString)["values"]
            .associate { it["id"].asString() to it["reviewReason"].asString() }

        assertEquals("FULFILMENT_UNRESOLVED", reasons[unfulfilled])
        assertEquals("REFUND_APPLY_UNRESOLVED", reasons[unapplied])
    }

    @Test
    fun `one order comes with its refunds and its history oldest first - ids count from 1 - and an unknown id is the order-not-found 404`() {
        val id = paid()

        val detail = read(mvc.perform(get("$admin/orders/$id").asOperator()).andExpect(status().isOk).andReturn().response.contentAsString)["value"]

        assertEquals(id, detail["order"]["id"].asString())
        assertEquals("pay_$id", detail["order"]["providerPaymentId"].asString())
        val events = detail["events"]
        assertEquals((1..events.size()).toList(), events.toList().map { it["id"].asInt() })
        assertEquals("ORDER_CREATED", events[0]["type"].asString())
        assertTrue(events.all { it.has("actor") && it.has("providerCode") && it.has("traceId") && it.has("createdAt") })
        mvc.perform(get("$admin/orders/ord_${"0".repeat(32)}").asOperator()).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("PAYMENT.ORDER_NOT_FOUND"))
    }

    // --- 직접 환불 ---

    @Test
    fun `refunding now is 200 with the refund line - origin OPERATOR - and the same key again is the same line, 200, with no second call to the provider`() {
        val id = paid()

        val first = refundNow(id, "same").andExpect(status().isOk)
            .andExpect(jsonPath("$.value.status").value("SUCCEEDED"))
            .andExpect(jsonPath("$.value.origin").value("OPERATOR"))
            .andExpect(jsonPath("$.value.reasonCode").value("OPERATOR"))
            .andExpect(jsonPath("$.value.decidedBy").value("operator_1"))
            .andReturn().response.contentAsString
        val calls = gateway.refundCalls.size
        val lookups = gateway.lookupCalls.size

        val again = refundNow(id, "same").andExpect(status().isOk).andReturn().response.contentAsString

        assertEquals(read(first)["value"]["id"], read(again)["value"]["id"])
        assertEquals(calls, gateway.refundCalls.size)
        assertEquals(lookups, gateway.lookupCalls.size, "a resend does not ask the provider")
        assertEquals(PaymentOrderStatus.REFUNDED, store.findOrder(id)!!.status)
    }

    @Test
    fun `a refund whose answer never came back is 202 PENDING`() {
        val id = paid()
        gateway.scriptRefund(ScriptedGateway.RefundScript.TimeoutNotApplied)

        refundNow(id).andExpect(status().isAccepted).andExpect(jsonPath("$.value.status").value("PENDING"))
    }

    @Test
    fun `a provider refusal is 402 refund declined with a category and the provider code, and the line is closed so the next refund is not blocked`() {
        val id = paid()
        gateway.scriptRefund(ScriptedGateway.RefundScript.Decline("NOT_ALLOWED_PARTIAL_REFUND"))

        refundNow(id, body = """{"reasonCode":"OPERATOR","amount":1000}""").andExpect(status().isPaymentRequired)
            .andExpect(jsonPath("$.code").value("PAYMENT.REFUND_DECLINED"))
            .andExpect(jsonPath("$.data.category").value("PARTIAL_NOT_ALLOWED"))
            .andExpect(jsonPath("$.data.providerCode").value("NOT_ALLOWED_PARTIAL_REFUND"))
        refundNow(id, body = """{"reasonCode":"OPERATOR","amount":1000}""").andExpect(status().isOk)
    }

    @Test
    fun `the refund input errors - too much 400, a reason that is not a code 400, no key 400, a missing order 404`() {
        val id = paid()
        refundNow(id, body = """{"reasonCode":"OPERATOR","amount":3001}""").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("PAYMENT.REFUND_AMOUNT_EXCEEDED"))
        refundNow(id, body = """{"reasonCode":"because"}""").andExpect(status().isBadRequest)
        mvc.perform(post("$admin/orders/$id/refunds").asOperator().contentType(MediaType.APPLICATION_JSON).content("""{"reasonCode":"OPERATOR"}""")).andExpect(status().isBadRequest)
        refundNow("ord_${"0".repeat(32)}").andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("PAYMENT.ORDER_NOT_FOUND"))
    }

    @Test
    fun `a direct refund is 409 in progress while a request is open on the order, and 409 not allowed on an order that was never paid`() {
        val account = account()
        val id = paid(account)
        userRequest(account, id)
        refundNow(id).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("PAYMENT.REFUND_IN_PROGRESS"))

        val unpaid = read(
            mvc.perform(post("$user/orders").asUser(account).header("Idempotency-Key", "u-${System.nanoTime()}").contentType(MediaType.APPLICATION_JSON).content("""{"itemType":"cheer","itemRef":"cheer-3000"}"""))
                .andReturn().response.contentAsString,
        )["value"]["order"]["id"].asString()
        refundNow(unpaid).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("PAYMENT.REFUND_NOT_ALLOWED"))
    }

    @Test
    fun `when the check before the refund cannot reach the provider nothing is sent - 503 unavailable - and no pending line stays`() {
        val id = paid()
        gateway.failLookups(1)
        val calls = gateway.refundCalls.size

        refundNow(id).andExpect(status().isServiceUnavailable).andExpect(jsonPath("$.code").value("PAYMENT.UNAVAILABLE"))

        assertEquals(calls, gateway.refundCalls.size)
        assertEquals(emptyList(), store.listRefunds(id).filter { it.status == PaymentRefundStatus.PENDING })
    }

    // --- 환불 요청 목록 · 승인 · 거절 ---

    @Test
    fun `the request list defaults to REQUESTED newest first with the operator fields, and a status shows the others`() {
        val account = account()
        val first = paid(account)
        val second = paid(account)
        val older = userRequest(account, first)
        val newer = userRequest(account, second)

        val rows = read(mvc.perform(get("$admin/refunds").asOperator()).andExpect(status().isOk).andReturn().response.contentAsString)["values"]
        val ours = rows.filter { it["id"].asString() in setOf(older, newer) }

        assertEquals(listOf(newer, older), ours.map { it["id"].asString() })
        assertEquals(account, ours[0]["requestedBy"].asString())
        assertTrue(ours[0].has("decidedBy") && ours[0].has("failureCode"))
        assertTrue(rows.all { it["status"].asString() == "REQUESTED" })
        mvc.perform(get("$admin/refunds?status=SUCCEEDED").asOperator()).andExpect(status().isOk)
    }

    @Test
    fun `approving a request refunds it as requested - 200 - and the second approval is 409 not allowed, an unknown line is 404 refund not found`() {
        val account = account()
        val id = paid(account)
        val line = userRequest(account, id, amount = 1000)

        mvc.perform(post("$admin/refunds/$line/approve").asOperator()).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.status").value("SUCCEEDED"))
            .andExpect(jsonPath("$.value.amount.amount").value(1000))
            .andExpect(jsonPath("$.value.origin").value("USER"))
            .andExpect(jsonPath("$.value.decidedBy").value("operator_1"))
        mvc.perform(post("$admin/refunds/$line/approve").asOperator()).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("PAYMENT.REFUND_NOT_ALLOWED"))
        mvc.perform(post("$admin/refunds/rfd_${"0".repeat(32)}/approve").asOperator()).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("PAYMENT.REFUND_NOT_FOUND"))
        assertEquals(PaymentOrderStatus.PARTIALLY_REFUNDED, store.findOrder(id)!!.status)
    }

    @Test
    fun `an approval the provider refuses is 402 and the request line is closed as failed`() {
        val account = account()
        val id = paid(account)
        val line = userRequest(account, id)
        gateway.scriptRefund(ScriptedGateway.RefundScript.Decline("EXCEED_MAX_REFUND_DUE"))

        mvc.perform(post("$admin/refunds/$line/approve").asOperator()).andExpect(status().isPaymentRequired)
            .andExpect(jsonPath("$.code").value("PAYMENT.REFUND_DECLINED"))
            .andExpect(jsonPath("$.data.category").value("PERIOD_EXPIRED"))
        assertEquals(PaymentRefundStatus.FAILED, store.findRefund(line)!!.status)
    }

    @Test
    fun `rejecting a request is 200 REJECTED and a decided line is 409`() {
        val account = account()
        val id = paid(account)
        val line = userRequest(account, id)

        mvc.perform(post("$admin/refunds/$line/reject").asOperator()).andExpect(status().isOk).andExpect(jsonPath("$.value.status").value("REJECTED"))
        mvc.perform(post("$admin/refunds/$line/reject").asOperator()).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("PAYMENT.REFUND_NOT_ALLOWED"))
        mvc.perform(get("$user/orders/$id").asUser(account)).andExpect(jsonPath("$.value.order.openRefundStatus").value(org.hamcrest.Matchers.nullValue()))
    }

    // --- 맞추기 ---

    @Test
    fun `sync returns the matched order and asks the provider`() {
        val id = paid()
        val lookups = gateway.lookupCalls.size

        mvc.perform(post("$admin/orders/$id/sync").asOperator()).andExpect(status().isOk).andExpect(jsonPath("$.value.id").value(id)).andExpect(jsonPath("$.value.providerPaymentId").value("pay_$id"))

        assertTrue(gateway.lookupCalls.size > lookups)
    }

    @Test
    fun `sync is 503 unavailable when the provider cannot be reached, 409 mode mismatch for an order of the other mode, 404 for a missing order`() {
        val id = paid()
        gateway.failLookups(1) { IllegalStateException("network down") }
        mvc.perform(post("$admin/orders/$id/sync").asOperator()).andExpect(status().isServiceUnavailable).andExpect(jsonPath("$.code").value("PAYMENT.UNAVAILABLE"))

        gateway.mode = dev.sumin.skeleton.payment.PaymentMode.LIVE
        try {
            mvc.perform(post("$admin/orders/$id/sync").asOperator()).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("PAYMENT.MODE_MISMATCH"))
            refundNow(id).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("PAYMENT.MODE_MISMATCH"))
        } finally {
            gateway.mode = dev.sumin.skeleton.payment.PaymentMode.TEST
        }
        mvc.perform(post("$admin/orders/ord_${"0".repeat(32)}/sync").asOperator()).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("PAYMENT.ORDER_NOT_FOUND"))
    }

    // --- 수신함 ---

    @Test
    fun `the inbox lists rows without the body, filters by status and pages by a cursor, and a retry hands back the row`() {
        val id = paid()
        gateway.authenticate("pay_$id", id, PaymentAmount(3000, "KRW"))
        repeat(3) { n ->
            mvc.perform(
                post("/api/v1/payments/webhooks/scripted/${dev.sumin.skeleton.payment.testing.PaymentLedgerKit.WEBHOOK_TOKEN}")
                    .contentType(MediaType.TEXT_PLAIN).header(ScriptedGateway.DELIVERY_HEADER, "evt-adm-$id-$n").content("eventType=PAYMENT_STATUS_CHANGED\npaymentKey=pay_$id\norderId=$id\nstatus=DONE\n"),
            )
        }

        val page = read(mvc.perform(get("$admin/webhook-events?size=2").asOperator()).andExpect(status().isOk).andReturn().response.contentAsString)
        assertEquals(2, page["values"].size())
        assertTrue(!page["values"][0].has("payload") && !page["values"][0].has("eventKey"))
        assertTrue(page["cursor"]["hasNext"].asBoolean())
        val next = read(mvc.perform(get("$admin/webhook-events?size=2&cursor=${page["cursor"]["nextCursor"].asString()}").asOperator()).andExpect(status().isOk).andReturn().response.contentAsString)
        assertTrue(next["values"].size() >= 1)
        val rowId = page["values"][0]["id"].asLong()

        mvc.perform(get("$admin/webhook-events?status=RECEIVED").asOperator()).andExpect(status().isOk)
        mvc.perform(post("$admin/webhook-events/$rowId/retry").asOperator()).andExpect(status().isOk).andExpect(jsonPath("$.value.id").value(rowId)).andExpect(jsonPath("$.value.status").value("RECEIVED"))
        mvc.perform(post("$admin/webhook-events/999999999/retry").asOperator()).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("PAYMENT.WEBHOOK_EVENT_NOT_FOUND"))
        mvc.perform(get("$admin/webhook-events?status=NOPE").asOperator()).andExpect(status().isBadRequest)
    }

    // --- 컨트롤러는 트랜잭션을 열지 않는다 ---

    @Test
    fun `no payment controller opens a transaction - the services that call the provider refuse to run inside one`() {
        listOf(PaymentController::class, PaymentOrderController::class, PaymentAdminController::class).forEach { type ->
            val annotated = (type.java.annotations.toList() + type.java.declaredMethods.flatMap { it.annotations.toList() }).map { it.annotationClass.qualifiedName.orEmpty() }
            assertTrue(annotated.none { it.contains("Transactional") }, "$type must not be transactional")
        }
    }
}
