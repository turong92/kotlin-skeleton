package dev.sumin.skeleton.payment.web

import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentOrderStatus
import dev.sumin.skeleton.payment.testing.InMemoryPaymentLedgerStore
import dev.sumin.skeleton.payment.testing.ScriptedGateway
import dev.sumin.skeleton.payment.web.PaymentHttpSupport.asOperator
import dev.sumin.skeleton.payment.web.PaymentHttpSupport.asUser
import dev.sumin.skeleton.payment.web.PaymentHttpSupport.read
import dev.sumin.skeleton.paymenttest.InMemoryLedgerBeans
import dev.sumin.skeleton.paymenttest.PaymentWebTestApplication
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

/** 운영자 경로 — 대표 경로마다 상태 코드 · 오류 코드. 권한 전수는 PaymentHttpAccessTest. */
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

    @Test
    fun `the order list carries the operator fields, newest first, filters by account and status, and q finds by exact id or provider number only`() {
        val account = account()
        val first = paid(account)
        val second = paid(account)

        val body = read(mvc.perform(get("$admin/orders?accountId=$account").asOperator()).andExpect(status().isOk).andReturn().response.contentAsString)

        assertEquals(listOf(second, first), body["values"].toList().map { it["id"].asString() })
        assertEquals(account, body["values"][0]["accountId"].asString())
        assertEquals("pay_$second", body["values"][0]["providerPaymentId"].asString())
        mvc.perform(get("$admin/orders?accountId=$account&status=REFUNDED").asOperator()).andExpect(jsonPath("$.values.length()").value(0))
        mvc.perform(get("$admin/orders?status=NOPE").asOperator()).andExpect(status().isBadRequest)
        mvc.perform(get("$admin/orders?q=pay_$first").asOperator()).andExpect(jsonPath("$.values[0].id").value(first))
        mvc.perform(get("$admin/orders?q=${first.take(10)}").asOperator()).andExpect(jsonPath("$.values.length()").value(0))
        mvc.perform(get("$admin/orders/$first").asOperator()).andExpect(status().isOk).andExpect(jsonPath("$.value.order.id").value(first))
        mvc.perform(get("$admin/orders/ord_${"0".repeat(32)}").asOperator()).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("PAYMENT.ORDER_NOT_FOUND"))
    }

    @Test
    fun `refunding now is 200 origin OPERATOR and the same key again is the same line with no second call to the provider`() {
        val id = paid()

        val first = refundNow(id, "same").andExpect(status().isOk)
            .andExpect(jsonPath("$.value.status").value("SUCCEEDED"))
            .andExpect(jsonPath("$.value.origin").value("OPERATOR"))
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
    fun `a provider refusal is 402 with a category and the line is closed so the next refund is not blocked`() {
        val id = paid()
        gateway.scriptRefund(ScriptedGateway.RefundScript.Decline("NOT_ALLOWED_PARTIAL_REFUND"))

        refundNow(id, body = """{"reasonCode":"OPERATOR","amount":1000}""").andExpect(status().isPaymentRequired)
            .andExpect(jsonPath("$.code").value("PAYMENT.REFUND_DECLINED"))
            .andExpect(jsonPath("$.data.category").value("PARTIAL_NOT_ALLOWED"))
        refundNow(id, body = """{"reasonCode":"OPERATOR","amount":1000}""").andExpect(status().isOk)
    }

    @Test
    fun `the refund errors - too much 400, no key 400, a missing order 404, an open request 409, an unpaid order 409`() {
        val account = account()
        val id = paid(account)
        refundNow(id, body = """{"reasonCode":"OPERATOR","amount":3001}""").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("PAYMENT.REFUND_AMOUNT_EXCEEDED"))
        mvc.perform(post("$admin/orders/$id/refunds").asOperator().contentType(MediaType.APPLICATION_JSON).content("""{"reasonCode":"OPERATOR"}""")).andExpect(status().isBadRequest)
        refundNow("ord_${"0".repeat(32)}").andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("PAYMENT.ORDER_NOT_FOUND"))

        userRequest(account, id)
        refundNow(id).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("PAYMENT.REFUND_IN_PROGRESS"))
        val unpaid = read(
            mvc.perform(post("$user/orders").asUser(account).header("Idempotency-Key", "u-${System.nanoTime()}").contentType(MediaType.APPLICATION_JSON).content("""{"itemType":"cheer","itemRef":"cheer-3000"}"""))
                .andReturn().response.contentAsString,
        )["value"]["order"]["id"].asString()
        refundNow(unpaid).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("PAYMENT.REFUND_NOT_ALLOWED"))
    }

    @Test
    fun `approving a request refunds it as requested and the second approval is 409, rejecting is 200 and a decided line is 409, an unknown line is 404`() {
        val account = account()
        val id = paid(account)
        val line = userRequest(account, id, amount = 1000)

        mvc.perform(post("$admin/refunds/$line/approve").asOperator()).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.status").value("SUCCEEDED"))
            .andExpect(jsonPath("$.value.amount.amount").value(1000))
            .andExpect(jsonPath("$.value.origin").value("USER"))
        mvc.perform(post("$admin/refunds/$line/approve").asOperator()).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("PAYMENT.REFUND_NOT_ALLOWED"))
        mvc.perform(post("$admin/refunds/rfd_${"0".repeat(32)}/approve").asOperator()).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("PAYMENT.REFUND_NOT_FOUND"))
        assertEquals(PaymentOrderStatus.PARTIALLY_REFUNDED, store.findOrder(id)!!.status)

        val other = userRequest(account, id, amount = 500)
        mvc.perform(post("$admin/refunds/$other/reject").asOperator()).andExpect(status().isOk).andExpect(jsonPath("$.value.status").value("REJECTED"))
        mvc.perform(post("$admin/refunds/$other/reject").asOperator()).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("PAYMENT.REFUND_NOT_ALLOWED"))
    }

    @Test
    fun `the inbox lists rows without the body, pages by a cursor, and a retry hands back the row or 404`() {
        val id = paid()
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
        val rowId = page["values"][0]["id"].asLong()

        mvc.perform(post("$admin/webhook-events/$rowId/retry").asOperator()).andExpect(status().isOk).andExpect(jsonPath("$.value.id").value(rowId))
        mvc.perform(post("$admin/webhook-events/999999999/retry").asOperator()).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("PAYMENT.WEBHOOK_EVENT_NOT_FOUND"))
    }

    @Test
    fun `no payment controller opens a transaction - the services that call the provider refuse to run inside one`() {
        listOf(PaymentController::class, PaymentOrderController::class, PaymentAdminController::class).forEach { type ->
            val annotated = (type.java.annotations.toList() + type.java.declaredMethods.flatMap { it.annotations.toList() }).map { it.annotationClass.qualifiedName.orEmpty() }
            assertTrue(annotated.none { it.contains("Transactional") }, "$type must not be transactional")
        }
    }
}
