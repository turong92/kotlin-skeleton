package dev.sumin.skeleton.payment.web

import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentLookupGateway
import dev.sumin.skeleton.payment.PaymentMode
import dev.sumin.skeleton.payment.PaymentRefundStatus
import dev.sumin.skeleton.payment.PaymentReviewReason
import dev.sumin.skeleton.payment.PaymentSnapshot
import dev.sumin.skeleton.payment.PaymentLookupQuery
import dev.sumin.skeleton.payment.testing.InMemoryPaymentLedgerStore
import dev.sumin.skeleton.payment.testing.ScriptedGateway
import dev.sumin.skeleton.payment.web.PaymentHttpSupport.asOperator
import dev.sumin.skeleton.payment.web.PaymentHttpSupport.asUser
import dev.sumin.skeleton.payment.web.PaymentHttpSupport.read
import dev.sumin.skeleton.paymenttest.InMemoryLedgerBeans
import dev.sumin.skeleton.paymenttest.PaymentWebTestApplication
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** 조회만 하는 결제사 — 환불 능력이 없다(`CAPABILITY_UNSUPPORTED` 가 나는 자리). */
class LookupOnlyGateway : PaymentLookupGateway {
    override val providerId = "lookuponly"
    override val mode = PaymentMode.TEST

    override fun lookup(query: PaymentLookupQuery): PaymentSnapshot? = null
}

@TestConfiguration(proxyBeanMethods = false)
class LookupOnlyBeans {
    @Bean fun lookupOnlyGateway() = LookupOnlyGateway()
}

/**
 * 프론트가 멱등 키를 놓는 근거가 되는 약속들의 HTTP 층 시험(계약 ③ · C3 · 운영자 응답의 모양) — 컨트롤러 줄을 지우면 빨개져야 한다.
 */
@SpringBootTest(
    classes = [PaymentWebTestApplication::class],
    properties = [
        "skeleton.payment.admin.enabled=true",
        "skeleton.payment.http.order-rate-limit.account-capacity=100000", "skeleton.payment.http.order-rate-limit.ip-capacity=100000",
    ],
)
@AutoConfigureMockMvc
@Import(InMemoryLedgerBeans::class, LookupOnlyBeans::class)
class PaymentAdminContractHttpTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var store: InMemoryPaymentLedgerStore
    @Autowired lateinit var gateway: ScriptedGateway

    private val admin = "/api/v1/admin/payments"
    private val user = "/api/v1/payments"
    private var sequence = 0

    private fun account() = "acc_ctr_${System.nanoTime()}_${sequence++}"

    private fun create(account: String, key: String = "k-${System.nanoTime()}"): String =
        read(
            mvc.perform(post("$user/orders").asUser(account).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content("""{"itemType":"cheer","itemRef":"cheer-3000"}"""))
                .andExpect(status().isCreated).andReturn().response.contentAsString,
        )["value"]["order"]["id"].asString()

    private fun paid(account: String = account()): String {
        val id = create(account)
        gateway.authenticate("pay_$id", id, PaymentAmount(3000, "KRW"))
        mvc.perform(post("$user/orders/$id/confirm").asUser(account).contentType(MediaType.APPLICATION_JSON).content("""{"providerPaymentId":"pay_$id","amount":3000}""")).andExpect(status().isOk)
        return id
    }

    private fun userRequest(account: String, orderId: String): String =
        read(
            mvc.perform(
                post("$user/orders/$orderId/refund-requests").asUser(account).header("Idempotency-Key", "r-${System.nanoTime()}")
                    .contentType(MediaType.APPLICATION_JSON).content("""{"reasonCode":"CUSTOMER_REQUEST","amount":3000}"""),
            ).andExpect(status().isCreated).andReturn().response.contentAsString,
        )["value"]["id"].asString()

    private fun refundNow(orderId: String, key: String = "op-${System.nanoTime()}", body: String = """{"reasonCode":"OPERATOR","amount":3000}"""): ResultActions =
        mvc.perform(post("$admin/orders/$orderId/refunds").asOperator().header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))

    private fun changeOrder(id: String, change: (dev.sumin.skeleton.payment.PaymentOrder) -> dev.sumin.skeleton.payment.PaymentOrder) {
        store.inTransaction { tx -> tx.updateOrder(change(tx.lockOrder(id)!!)) }
    }

    @Test
    fun `a refund or an approval whose provider cannot be found is 503 unavailable and one that cannot refund from the server is 409 unsupported - nothing was sent and no pending line stays`() {
        val account = account()
        val ghost = paid(account)
        val ghostRequest = userRequest(account, ghost)
        changeOrder(ghost) { it.copy(provider = "ghost") }
        val noRefund = paid(account())
        changeOrder(noRefund) { it.copy(provider = "lookuponly") }
        val noRefundAccount = account()
        val noRefundRequestOrder = paid(noRefundAccount)
        val noRefundRequest = userRequest(noRefundAccount, noRefundRequestOrder)
        changeOrder(noRefundRequestOrder) { it.copy(provider = "lookuponly") }
        val calls = gateway.refundCalls.size

        refundNow(ghost).andExpect(status().isServiceUnavailable).andExpect(jsonPath("$.code").value("PAYMENT.UNAVAILABLE"))
        refundNow(noRefund).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("PAYMENT.REFUND_UNSUPPORTED"))
        mvc.perform(post("$admin/refunds/$ghostRequest/approve").asOperator()).andExpect(status().isServiceUnavailable).andExpect(jsonPath("$.code").value("PAYMENT.UNAVAILABLE"))
        mvc.perform(post("$admin/refunds/$noRefundRequest/approve").asOperator()).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("PAYMENT.REFUND_UNSUPPORTED"))

        assertEquals(calls, gateway.refundCalls.size)
        assertEquals(PaymentRefundStatus.REQUESTED, store.findRefund(ghostRequest)!!.status)
        assertEquals(emptyList(), store.listRefunds(ghost).filter { it.status == PaymentRefundStatus.PENDING })
    }

    @Test
    fun `the operator views say why a server refund is blocked - unsupported, provider off, key mode - and nothing when it can be sent`() {
        val account = account()
        val ok = paid(account)
        val okRequest = userRequest(account, ok)
        val ghost = paid(account())
        changeOrder(ghost) { it.copy(provider = "ghost") }
        val noRefund = paid(account())
        changeOrder(noRefund) { it.copy(provider = "lookuponly") }
        val live = paid(account())
        changeOrder(live) { it.copy(providerMode = PaymentMode.LIVE) }

        mvc.perform(get("$admin/orders/$ok").asOperator()).andExpect(jsonPath("$.value.order.refundBlock").isEmpty).andExpect(jsonPath("$.value.refunds[0].refundBlock").isEmpty)
        mvc.perform(get("$admin/orders/$ghost").asOperator()).andExpect(jsonPath("$.value.order.refundBlock").value("PROVIDER_OFF"))
        mvc.perform(get("$admin/orders/$noRefund").asOperator()).andExpect(jsonPath("$.value.order.refundBlock").value("UNSUPPORTED"))
        mvc.perform(get("$admin/orders/$live").asOperator()).andExpect(jsonPath("$.value.order.refundBlock").value("MODE_MISMATCH"))
        mvc.perform(get("$admin/orders?q=$noRefund").asOperator()).andExpect(jsonPath("$.values[0].refundBlock").value("UNSUPPORTED"))
        // 환불 요청 목록의 줄에도 같은 칸과 주문 종류가 있다
        val request = read(mvc.perform(get("$admin/refunds").asOperator()).andExpect(status().isOk).andReturn().response.contentAsString)["values"].toList().first { it["id"].asString() == okRequest }
        assertEquals("ONE_TIME", request["orderKind"].asString())
        assertTrue(request["refundBlock"].isNull)
        assertEquals(false, request["keepSubscription"].asBoolean())
    }

    @Test
    fun `endSubscription must be true or false, and it is checked before any line or provider call`() {
        val id = paid()
        val lookups = gateway.lookupCalls.size

        refundNow(id, body = """{"reasonCode":"OPERATOR","endSubscription":"no"}""").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("COMMON.VALIDATION_FAILED"))

        assertEquals(lookups, gateway.lookupCalls.size)
        assertEquals(emptyList(), store.listRefunds(id))
        refundNow(id, body = """{"reasonCode":"OPERATOR","amount":1000,"endSubscription":false}""").andExpect(status().isOk).andExpect(jsonPath("$.value.keepSubscription").value(true))
    }

    @Test
    fun `input the server refuses - amount 0 or negative, a wrong type, broken JSON - is 400 with a validation code before any line, any provider call or any change`() {
        val id = paid()
        val lookups = gateway.lookupCalls.size
        val calls = gateway.refundCalls.size
        val before = store.findOrder(id)

        refundNow(id, body = """{"reasonCode":"OPERATOR","amount":0}""").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("COMMON.VALIDATION_FAILED"))
        refundNow(id, body = """{"reasonCode":"OPERATOR","amount":"3000"}""").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("COMMON.VALIDATION_FAILED"))
        refundNow(id, body = """{"reasonCode":"OPERATOR",""").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("COMMON.MALFORMED_REQUEST"))

        assertEquals(lookups, gateway.lookupCalls.size, "no provider call, not even the pre-check lookup")
        assertEquals(calls, gateway.refundCalls.size)
        assertEquals(emptyList(), store.listRefunds(id))
        assertEquals(before, store.findOrder(id))
    }

    @Test
    fun `a provider refusing the identifier after the line is claimed does not come out as a 400 - the answer is unknown, 202 PENDING, so the front end keeps its key`() {
        val id = paid()
        gateway.scriptRefund(ScriptedGateway.RefundScript.RejectIdentifier)

        refundNow(id).andExpect(status().isAccepted).andExpect(jsonPath("$.value.status").value("PENDING"))

        assertEquals(1, store.listRefunds(id).count { it.status == PaymentRefundStatus.PENDING })
    }

    @Test
    fun `an erased account's rows are hidden from the default list and shown with includeErased, a q lookup and review`() {
        val account = account()
        val id = paid(account)
        val erasedAt = Instant.parse("2026-10-01T00:00:00Z")
        changeOrder(id) { it.copy(accountErasedAt = erasedAt, reviewReason = PaymentReviewReason.AMOUNT_MISMATCH) }

        fun ids(query: String): List<String> = read(mvc.perform(get("$admin/orders?accountId=$account$query").asOperator()).andExpect(status().isOk).andReturn().response.contentAsString)["values"].toList().map { it["id"].asString() }

        assertEquals(emptyList<String>(), ids(""))
        assertEquals(listOf(id), ids("&includeErased=true"))
        assertEquals(listOf(id), ids("&review=true"))
        mvc.perform(get("$admin/orders?q=$id").asOperator()).andExpect(jsonPath("$.values[0].id").value(id))
    }

    @Test
    fun `sync is 404 when the provider does not know the payment, and a mode mismatch found by sync stays flagged though it ends in an error`() {
        val unknown = create(account())
        mvc.perform(post("$admin/orders/$unknown/sync").asOperator()).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("PAYMENT.PROVIDER_PAYMENT_NOT_FOUND"))
        val unpaid = paid() // 결제된 주문 — 선점한 적 없는 PENDING 에는 표시를 찍지 않는다
        gateway.mode = PaymentMode.LIVE
        try {
            mvc.perform(post("$admin/orders/$unpaid/sync").asOperator()).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("PAYMENT.MODE_MISMATCH"))
        } finally {
            gateway.mode = PaymentMode.TEST
        }

        assertEquals(PaymentReviewReason.MODE_MISMATCH, store.findOrder(unpaid)!!.reviewReason)
    }

    @Test
    fun `a provider error on sync is 502 whose detail is a fixed sentence - the provider's own words are only in the operator data`() {
        val id = paid()
        gateway.failLookups(1) { gateway.providerErrorSaying(502, "SECRET_PROVIDER_WORDS") }

        val body = mvc.perform(post("$admin/orders/$id/sync").asOperator()).andExpect(status().isBadGateway).andExpect(jsonPath("$.code").value("PAYMENT.PROVIDER_ERROR")).andReturn().response.contentAsString

        assertTrue("SECRET_PROVIDER_WORDS" !in read(body)["detail"].asString(), "detail is fixed: $body")
        assertTrue(read(body)["data"].toString().contains("SECRET_PROVIDER_WORDS"), "the provider's words are in data: $body")
    }
}
