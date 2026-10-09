package dev.sumin.skeleton.payment.web

import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentLookupGateway
import dev.sumin.skeleton.payment.PaymentMode
import dev.sumin.skeleton.payment.PaymentOrderStatus
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
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.hamcrest.Matchers.nullValue
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
import tools.jackson.databind.JsonNode

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

    // --- 계약 ③: 보내지 않았다 = 503 UNAVAILABLE (선점 전의 환불 전 확인 구간에서만) ---

    @Test
    fun `a refund or an approval whose provider cannot be found or cannot refund is 503 unavailable - nothing was sent and no pending line stays`() {
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
        refundNow(noRefund).andExpect(status().isServiceUnavailable).andExpect(jsonPath("$.code").value("PAYMENT.UNAVAILABLE"))
        mvc.perform(post("$admin/refunds/$ghostRequest/approve").asOperator()).andExpect(status().isServiceUnavailable).andExpect(jsonPath("$.code").value("PAYMENT.UNAVAILABLE"))
        mvc.perform(post("$admin/refunds/$noRefundRequest/approve").asOperator()).andExpect(status().isServiceUnavailable).andExpect(jsonPath("$.code").value("PAYMENT.UNAVAILABLE"))

        assertEquals(calls, gateway.refundCalls.size)
        assertEquals(PaymentRefundStatus.REQUESTED, store.findRefund(ghostRequest)!!.status)
        assertEquals(emptyList(), store.listRefunds(ghost).filter { it.status == PaymentRefundStatus.PENDING })
    }

    // --- 계약: 검증 코드는 환불 줄을 선점하기 전에만 나간다 ---

    @Test
    fun `input the server refuses - amount 0 or negative, a wrong type, broken JSON - is 400 with a validation code before any line, any provider call or any change`() {
        val id = paid()
        val lookups = gateway.lookupCalls.size
        val calls = gateway.refundCalls.size
        val before = store.findOrder(id)

        refundNow(id, body = """{"reasonCode":"OPERATOR","amount":0}""").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("COMMON.VALIDATION_FAILED"))
        refundNow(id, body = """{"reasonCode":"OPERATOR","amount":-5}""").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("COMMON.VALIDATION_FAILED"))
        refundNow(id, body = """{"reasonCode":"OPERATOR","amount":"3000"}""").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("COMMON.VALIDATION_FAILED"))
        refundNow(id, body = """{"reasonCode":7,"amount":3000}""").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("COMMON.VALIDATION_FAILED"))
        refundNow(id, body = """{"reasonCode":"OPERATOR",""").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("COMMON.MALFORMED_REQUEST"))
        mvc.perform(get("$admin/orders?size=abc").asOperator()).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("COMMON.PARAMETER_VALIDATION_FAILED"))

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

    // --- C3 · 같은 키 ---

    @Test
    fun `the same key again after the provider refused returns that FAILED line as 200 and does not ask the provider again`() {
        val id = paid()
        gateway.scriptRefund(ScriptedGateway.RefundScript.Decline("NOT_ALLOWED_PARTIAL_REFUND"))
        val body = """{"reasonCode":"OPERATOR","amount":1000}"""
        refundNow(id, "k-declined", body).andExpect(status().isPaymentRequired)
        val calls = gateway.refundCalls.size
        val lookups = gateway.lookupCalls.size

        refundNow(id, "k-declined", body).andExpect(status().isOk).andExpect(jsonPath("$.value.status").value("FAILED"))

        assertEquals(calls, gateway.refundCalls.size)
        assertEquals(lookups, gateway.lookupCalls.size)
    }

    @Test
    fun `a direct refund with the same key and another body is 409 idempotency conflict`() {
        val id = paid()
        refundNow(id, "k-conflict", """{"reasonCode":"OPERATOR","amount":1000}""").andExpect(status().isOk)

        refundNow(id, "k-conflict", """{"reasonCode":"OPERATOR","amount":2000}""").andExpect(status().isConflict).andExpect(jsonPath("$.code").value("PAYMENT.IDEMPOTENCY_CONFLICT"))
        refundNow(id, "k-conflict", """{"reasonCode":"DUPLICATE","amount":1000}""").andExpect(status().isConflict).andExpect(jsonPath("$.code").value("PAYMENT.IDEMPOTENCY_CONFLICT"))
    }

    // --- 탈퇴한 계정의 줄: 기본 숨김과 예외 ---

    @Test
    fun `an erased account's rows are hidden from the default list and shown with includeErased, a q lookup and review - and every operator shape carries accountErasedAt`() {
        val account = account()
        val id = paid(account)
        val erasedAt = Instant.parse("2026-10-01T00:00:00Z")
        changeOrder(id) { it.copy(accountErasedAt = erasedAt, reviewReason = PaymentReviewReason.AMOUNT_MISMATCH) }

        fun ids(query: String): List<String> = read(mvc.perform(get("$admin/orders?accountId=$account$query").asOperator()).andExpect(status().isOk).andReturn().response.contentAsString)["values"].toList().map { it["id"].asString() }

        assertEquals(emptyList<String>(), ids(""))
        assertEquals(listOf(id), ids("&includeErased=true"))
        assertEquals(listOf(id), ids("&review=true"))
        mvc.perform(get("$admin/orders?q=$id").asOperator()).andExpect(jsonPath("$.values[0].id").value(id)).andExpect(jsonPath("$.values[0].accountErasedAt").value("2026-10-01T00:00:00Z"))
        mvc.perform(get("$admin/orders/$id").asOperator()).andExpect(jsonPath("$.value.order.accountErasedAt").value("2026-10-01T00:00:00Z"))
        mvc.perform(post("$admin/orders/$id/sync").asOperator()).andExpect(status().isOk).andExpect(jsonPath("$.value.accountErasedAt").value("2026-10-01T00:00:00Z"))
    }

    @Test
    fun `accountErasedAt is a field of every operator order shape even while the account is there - null`() {
        val id = paid()

        val shapes = listOf(
            read(mvc.perform(get("$admin/orders?q=$id").asOperator()).andReturn().response.contentAsString)["values"][0],
            read(mvc.perform(get("$admin/orders/$id").asOperator()).andReturn().response.contentAsString)["value"]["order"],
            read(mvc.perform(post("$admin/orders/$id/sync").asOperator()).andReturn().response.contentAsString)["value"],
        )

        shapes.forEach { assertTrue(it.has("accountErasedAt") && it["accountErasedAt"].isNull, "shape: $it") }
    }

    // --- 맞추기 ---

    @Test
    fun `sync is 404 PROVIDER_PAYMENT_NOT_FOUND when the provider does not know the payment - the ledger row is as it was`() {
        val unpaid = create(account())
        val before = store.findOrder(unpaid)

        mvc.perform(post("$admin/orders/$unpaid/sync").asOperator()).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("PAYMENT.PROVIDER_PAYMENT_NOT_FOUND"))

        assertEquals(before, store.findOrder(unpaid))
    }

    @Test
    fun `a mode mismatch found by sync stays flagged - what sync already did is committed, even though it ends in an error`() {
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

    @Test
    fun `the sync response and the order detail carry the same reviewReason, derived ones included`() {
        val account = account()
        val stale = create(account)
        gateway.authenticate("pay_$stale", stale, PaymentAmount(3000, "KRW"))
        changeOrder(stale) { it.copy(status = PaymentOrderStatus.CONFIRMING, providerPaymentId = "pay_$stale", confirmStartedAt = Instant.now().minus(Duration.ofHours(5))) }
        val unfulfilled = paid(account)
        changeOrder(unfulfilled) { it.copy(fulfilledAt = null, paidAt = Instant.now().minus(Duration.ofHours(3))) }
        val unapplied = paid(account)
        changeOrder(unapplied) { it.copy(fulfilledAt = Instant.now()) }
        store.inTransaction { tx ->
            tx.lockOrder(unapplied)
            tx.insertRefundIfAbsent(
                dev.sumin.skeleton.payment.testing.PaymentTestOrders.refund(id = "rfd_" + "6".repeat(32), orderId = unapplied, status = PaymentRefundStatus.SUCCEEDED, now = Instant.now().minus(Duration.ofHours(3)))
                    .copy(completedAt = Instant.now().minus(Duration.ofHours(3))),
            )
        }

        mapOf(stale to "STALE_CONFIRMING", unfulfilled to "FULFILMENT_UNRESOLVED", unapplied to "REFUND_APPLY_UNRESOLVED").forEach { (id, expected) ->
            val synced = read(mvc.perform(post("$admin/orders/$id/sync").asOperator()).andExpect(status().isOk).andReturn().response.contentAsString)["value"]
            val detail = read(mvc.perform(get("$admin/orders/$id").asOperator()).andReturn().response.contentAsString)["value"]["order"]
            assertEquals(expected, synced["reviewReason"].asString(), "sync of $id")
            assertEquals(synced["reviewReason"], detail["reviewReason"], "detail of $id")
        }
    }

    // --- 모든 응답 DTO 의 칸 ---

    @Test
    fun `every order object in every response has openRefundStatus and every refund line has closedBy - null is allowed, a missing field is not`() {
        val account = account()
        val id = paid(account)
        val line = userRequest(account, id)
        val responses: List<JsonNode> = listOf(
            mvc.perform(get("$user/orders/$id").asUser(account)),
            mvc.perform(get("$user/orders").asUser(account)),
            mvc.perform(post("$user/orders/$line/withdraw".replace("orders/$line", "refunds/$line")).asUser(account)),
            mvc.perform(post("$user/orders/$id/abandon").asUser(account)),
            mvc.perform(post("$user/orders").asUser(account).header("Idempotency-Key", "shape-1").contentType(MediaType.APPLICATION_JSON).content("""{"itemType":"cheer","itemRef":"cheer-3000"}""")),
            mvc.perform(post("$user/orders").asUser(account).header("Idempotency-Key", "shape-1").contentType(MediaType.APPLICATION_JSON).content("""{"itemType":"cheer","itemRef":"cheer-3000"}""")),
            mvc.perform(post("$user/orders/$id/confirm").asUser(account).contentType(MediaType.APPLICATION_JSON).content("""{"providerPaymentId":"pay_$id","amount":3000}""")),
            mvc.perform(post("$user/orders/$id/refund-requests").asUser(account).header("Idempotency-Key", "shape-2").contentType(MediaType.APPLICATION_JSON).content("""{"reasonCode":"CUSTOMER_REQUEST"}""")),
            mvc.perform(get("$admin/orders?accountId=$account").asOperator()),
            mvc.perform(get("$admin/orders/$id").asOperator()),
            mvc.perform(post("$admin/orders/$id/sync").asOperator()),
            mvc.perform(get("$admin/refunds?status=WITHDRAWN").asOperator()),
            mvc.perform(post("$admin/orders/$id/refunds").asOperator().header("Idempotency-Key", "shape-3").contentType(MediaType.APPLICATION_JSON).content("""{"reasonCode":"OPERATOR","amount":1000}""")),
        ).map { read(it.andReturn().response.contentAsString) }

        var orders = 0
        var refunds = 0
        fun walk(node: JsonNode) {
            if (node.isObject) {
                if (node.has("refundableAmount")) {
                    orders++
                    assertTrue(node.has("openRefundStatus"), "an order object without openRefundStatus: $node")
                }
                if (node.has("reasonCode") && node.has("origin") && node.has("orderId")) {
                    refunds++
                    assertTrue(node.has("closedBy"), "a refund line without closedBy: $node")
                }
                node.properties().forEach { (_, child) -> walk(child) }
            }
            if (node.isArray) node.forEach(::walk)
        }
        responses.forEach(::walk)
        assertTrue(orders >= 8 && refunds >= 3, "the walk really met orders ($orders) and refund lines ($refunds)")
    }

    @Test
    fun `an operator order and an operator refund line have exactly these fields in exactly this order - the front end reads this shape`() {
        val account = account()
        val id = paid(account)
        val line = userRequest(account, id)
        val orderFields = listOf(
            "id", "status", "kind", "itemType", "itemRef", "quantity", "orderName", "amount", "refundedAmount", "refundableAmount", "refundPending", "openRefundStatus", "method", "card", "testMode",
            "failureCode", "createdAt", "paidAt", "fulfilled", "accountId", "provider", "providerPaymentId", "reviewReason", "accountErasedAt",
        )
        val refundFields = listOf("id", "orderId", "status", "amount", "reasonCode", "origin", "requestedAt", "completedAt", "closedBy", "requestedBy", "decidedBy", "failureCode")

        val order = read(mvc.perform(post("$admin/orders/$id/sync").asOperator()).andExpect(status().isOk).andReturn().response.contentAsString)["value"]
        assertEquals(orderFields, order.propertyNames().toList())
        val listed = read(mvc.perform(get("$admin/orders?accountId=$account").asOperator()).andReturn().response.contentAsString)["values"][0]
        assertEquals(orderFields, listed.propertyNames().toList())
        val detail = read(mvc.perform(get("$admin/orders/$id").asOperator()).andReturn().response.contentAsString)["value"]
        assertEquals(orderFields, detail["order"].propertyNames().toList())
        assertEquals(refundFields, detail["refunds"][0].propertyNames().toList())
        val refund = read(mvc.perform(get("$admin/refunds").asOperator()).andReturn().response.contentAsString)["values"].first { it["id"].asString() == line }
        assertEquals(refundFields, refund.propertyNames().toList())
    }

    @Test
    fun `the same key again for an order that already has a refund open says so - refundPending, openRefundStatus and refundableAmount are the order's real ones`() {
        val account = account()
        val key = "replay-open-refund"
        val created = read(
            mvc.perform(post("$user/orders").asUser(account).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content("""{"itemType":"cheer","itemRef":"cheer-3000"}""")).andReturn().response.contentAsString,
        )["value"]["order"]["id"].asString()
        gateway.authenticate("pay_$created", created, PaymentAmount(3000, "KRW"))
        mvc.perform(post("$user/orders/$created/confirm").asUser(account).contentType(MediaType.APPLICATION_JSON).content("""{"providerPaymentId":"pay_$created","amount":3000}""")).andExpect(status().isOk)
        userRequest(account, created)

        mvc.perform(post("$user/orders").asUser(account).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content("""{"itemType":"cheer","itemRef":"cheer-3000"}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value.order.refundPending").value(true))
            .andExpect(jsonPath("$.value.order.openRefundStatus").value("REQUESTED"))
            .andExpect(jsonPath("$.value.checkout").value(nullValue()))
    }
}
