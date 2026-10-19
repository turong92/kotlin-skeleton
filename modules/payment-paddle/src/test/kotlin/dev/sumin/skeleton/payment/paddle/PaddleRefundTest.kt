package dev.sumin.skeleton.payment.paddle

import dev.sumin.skeleton.payment.IdempotencyKey
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentFailureOutcome
import dev.sumin.skeleton.payment.PaymentFailures
import dev.sumin.skeleton.payment.PaymentLookupQuery
import dev.sumin.skeleton.payment.PaymentOperationStatus
import dev.sumin.skeleton.payment.PaymentRefundRequest
import dev.sumin.skeleton.payment.PaymentWebhookRejectedException
import dev.sumin.skeleton.payment.ProviderPaymentStatus
import dev.sumin.skeleton.payment.PushedEvents
import dev.sumin.skeleton.payment.PushedRefund
import dev.sumin.skeleton.payment.PushedRefundKind
import dev.sumin.skeleton.payment.PushedRefundStatus
import dev.sumin.skeleton.payment.RefundDeclineCategory
import dev.sumin.skeleton.payment.WebhookDelivery
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** 서버가 Paddle 에 환불(adjustment)을 시키고 거래를 조회하는 길 - 가짜 Paddle API 로. 이중 환불 · 금액 · 모름/확정 분류가 깨지면 사고 나는 곳이다. */
class PaddleRefundTest {
    private fun request(amount: Long = 499, currency: String = "USD") =
        PaymentRefundRequest(providerPaymentId = "txn_1", amount = PaymentAmount(amount, currency), reason = "Operator refund", idempotencyKey = IdempotencyKey("rfd_abc"))

    private fun adjustmentAnswer(status: String, total: String = "499") = """{"data":{"id":"adj_1","action":"refund","status":"$status","transaction_id":"txn_1","totals":{"total":"$total"}}}"""

    @Test
    fun `refunding the whole untouched transaction is a full adjustment with the ledger's reason and line id, and an immediate approval is REFUNDED with the adjustment number`() {
        FakePaddleApi().use { api ->
            api.on("GET", "/transactions/txn_1", 200, transactionBody())
            api.on("POST", "/adjustments", 201, adjustmentAnswer("approved"))

            val result = gateway(api.baseUrl).refund(request())

            assertThat(result.status).isEqualTo(PaymentOperationStatus.REFUNDED)
            assertThat(result.providerOperationId).isEqualTo("adj_1")
            assertThat(result.amount).isEqualTo(PaymentAmount(499, "USD"))
            val post = api.requests.single { it.method == "POST" }
            assertThat(post.auth).isEqualTo("Bearer pdl_sdbx_apikey_x")
            assertThat(post.body).contains("\"action\":\"refund\"").contains("\"transaction_id\":\"txn_1\"").contains("\"type\":\"full\"").contains("rfd_abc").doesNotContain("tax_mode").doesNotContain("items")
            assertThat(api.requests.first().path).isEqualTo("/transactions/txn_1")
        }
    }

    @Test
    fun `a partial refund names the one line item and sends the tax-inclusive amount without a tax mode`() {
        FakePaddleApi().use { api ->
            api.on("GET", "/transactions/txn_1", 200, transactionBody())
            api.on("POST", "/adjustments", 201, adjustmentAnswer("pending_approval", "100"))

            val result = gateway(api.baseUrl).refund(request(amount = 100))

            assertThat(result.status).isEqualTo(PaymentOperationStatus.PENDING)
            assertThat(result.providerOperationId).isEqualTo("adj_1")
            val post = api.requests.single { it.method == "POST" }
            assertThat(post.body).contains("\"type\":\"partial\"").contains("\"item_id\":\"txnitm_1\"").contains("\"amount\":\"100\"").doesNotContain("tax_mode")
        }
    }

    @Test
    fun `the rest of a transaction that already has an approved refund is a partial adjustment, never a full one`() {
        FakePaddleApi().use { api ->
            api.on("GET", "/transactions/txn_1", 200, transactionBody(adjustments = listOf(listOf("adj_0", "refund", "approved", "100"))))
            api.on("POST", "/adjustments", 201, adjustmentAnswer("approved", "399"))

            gateway(api.baseUrl).refund(request(amount = 399))

            assertThat(api.requests.single { it.method == "POST" }.body).contains("\"type\":\"partial\"").contains("\"amount\":\"399\"")
        }
    }

    @Test
    fun `a transaction with more than one line item cannot be refunded in part - a definitive refusal that sends nothing`() {
        FakePaddleApi().use { api ->
            api.on("GET", "/transactions/txn_1", 200, transactionBody(items = 2))

            val failure = catchThrowable { gateway(api.baseUrl).refund(request(amount = 100)) }

            assertThat(PaymentFailures.outcomeOf(failure)).isEqualTo(PaymentFailureOutcome.DEFINITIVE)
            assertThat(gateway(api.baseUrl).refundDeclineCategory(PaymentFailures.providerCodeOf(failure))).isEqualTo(RefundDeclineCategory.PARTIAL_NOT_ALLOWED)
            assertThat(api.requests.none { it.method == "POST" }).isTrue()
        }
    }

    @Test
    fun `Paddle's documented refusals are definitive and the ones an earlier request may have caused are not`() {
        FakePaddleApi().use { api ->
            api.on("GET", "/transactions/txn_1", 200, transactionBody())
            fun failureOf(status: Int, code: String): Throwable {
                api.on("POST", "/adjustments", status, """{"error":{"type":"request_error","code":"$code","detail":"long text that must not travel"}}""")
                return catchThrowable { gateway(api.baseUrl).refund(request()) }
            }

            assertThat(PaymentFailures.outcomeOf(failureOf(400, "adjustment_transaction_invalid_status_for_refund"))).isEqualTo(PaymentFailureOutcome.DEFINITIVE)
            assertThat(PaymentFailures.outcomeOf(failureOf(403, "forbidden"))).isEqualTo(PaymentFailureOutcome.DEFINITIVE)
            assertThat(PaymentFailures.outcomeOf(failureOf(409, "adjustment_pending_refund_request"))).isEqualTo(PaymentFailureOutcome.UNKNOWN)
            assertThat(PaymentFailures.outcomeOf(failureOf(400, "adjustment_total_above_remaining_allowed"))).isEqualTo(PaymentFailureOutcome.UNKNOWN)
            assertThat(PaymentFailures.outcomeOf(failureOf(400, "adjustment_transaction_item_has_already_been_fully_adjusted"))).isEqualTo(PaymentFailureOutcome.UNKNOWN)
            assertThat(PaymentFailures.outcomeOf(failureOf(500, "internal_error"))).isEqualTo(PaymentFailureOutcome.UNKNOWN)
            assertThat(PaymentFailures.outcomeOf(failureOf(400, "a_code_nobody_documented"))).isEqualTo(PaymentFailureOutcome.UNKNOWN)
        }
    }

    @Test
    fun `an answer that cannot be read is unknown, not a refund`() {
        FakePaddleApi().use { api ->
            api.on("GET", "/transactions/txn_1", 200, transactionBody())
            api.on("POST", "/adjustments", 201, "not json")

            val failure = catchThrowable { gateway(api.baseUrl).refund(request()) }

            assertThat(failure).isNotNull()
            assertThat(PaymentFailures.outcomeOf(failure)).isEqualTo(PaymentFailureOutcome.UNKNOWN)
        }
    }

    @Test
    fun `a lookup reads approved refunds and chargebacks as taken back, holds pending refunds apart, and leaves reversed ones out`() {
        FakePaddleApi().use { api ->
            api.on(
                "GET", "/transactions/txn_1", 200,
                transactionBody(
                    adjustments = listOf(
                        listOf("adj_a", "refund", "approved", "100"), listOf("adj_p", "refund", "pending_approval", "50"),
                        listOf("adj_r", "refund", "rejected", "30"), listOf("adj_c", "chargeback", "reversed", "70"), listOf("adj_w", "chargeback_warning", "approved", "20"),
                        listOf("adj_cr", "credit", "approved", "10"),
                    ),
                ),
            )

            val snapshot = checkNotNull(gateway(api.baseUrl).lookup(PaymentLookupQuery("txn_1", null)))

            assertThat(snapshot.status).isEqualTo(ProviderPaymentStatus.PARTIALLY_REFUNDED)
            assertThat(snapshot.total).isEqualTo(PaymentAmount(499, "USD"))
            assertThat(snapshot.remaining).isEqualTo(PaymentAmount(379, "USD"))
            assertThat(snapshot.refunds.map { it.providerRefundId }).containsExactlyInAnyOrder("adj_a", "adj_w")
            assertThat(snapshot.pendingRefunds.map { it.providerRefundId }).containsExactly("adj_p")
            assertThat(api.requests.single().path).isEqualTo("/transactions/txn_1")
        }
    }

    @Test
    fun `a transaction that is not completed is not received, a canceled one failed, and a fully refunded one is REFUNDED`() {
        FakePaddleApi().use { api ->
            val gateway = gateway(api.baseUrl)
            api.on("GET", "/transactions/txn_1", 200, transactionBody(status = "billed"))
            assertThat(gateway.lookup(PaymentLookupQuery("txn_1", null))!!.status).isEqualTo(ProviderPaymentStatus.IN_PROGRESS)
            api.on("GET", "/transactions/txn_1", 200, transactionBody(status = "canceled"))
            assertThat(gateway.lookup(PaymentLookupQuery("txn_1", null))!!.status).isEqualTo(ProviderPaymentStatus.FAILED)
            api.on("GET", "/transactions/txn_1", 200, transactionBody(adjustments = listOf(listOf("adj_a", "refund", "approved", "499"))))
            assertThat(gateway.lookup(PaymentLookupQuery("txn_1", null))!!.status).isEqualTo(ProviderPaymentStatus.REFUNDED)
        }
    }

    @Test
    fun `a lookup by order number alone asks nobody, and a transaction Paddle does not know is null`() {
        FakePaddleApi().use { api ->
            val gateway = gateway(api.baseUrl)
            assertThat(gateway.answersByOrderId).isFalse()
            assertThat(gateway.lookup(PaymentLookupQuery(null, "ord_1"))).isNull()
            assertThat(api.requests).isEmpty()

            api.on("GET", "/transactions/txn_1", 404, """{"error":{"type":"request_error","code":"not_found"}}""")
            assertThat(gateway.lookup(PaymentLookupQuery("txn_1", null))).isNull()
        }
    }

    @Test
    fun `a lookup failure is never a definitive answer`() {
        FakePaddleApi().use { api ->
            api.on("GET", "/transactions/txn_1", 403, """{"error":{"code":"forbidden"}}""")

            val failure = catchThrowable { gateway(api.baseUrl).lookup(PaymentLookupQuery("txn_1", null)) }

            assertThat(PaymentFailures.outcomeOf(failure)).isEqualTo(PaymentFailureOutcome.UNKNOWN)
        }
    }

    @Test
    fun `an adjustment webhook is verified like the others and becomes a refund decision, and credits and other kinds are not handled`() {
        val gateway = gateway()
        fun deliver(body: String) = gateway.parse(WebhookDelivery(mapOf("Paddle-Signature" to sign(body)), body.toByteArray()))

        val created = adjustmentEvent(type = "adjustment.created", status = "pending_approval")
        assertThat(deliver(created).resourceType).isEqualTo(PushedEvents.RESOURCE_TYPE)
        assertThat(deliver(created).resourceId).isEqualTo("adj_1")
        val event = gateway.interpret(created) as PushedRefund
        assertThat(event.kind).isEqualTo(PushedRefundKind.REFUND)
        assertThat(event.status).isEqualTo(PushedRefundStatus.PENDING_APPROVAL)
        assertThat(event.providerPaymentId).isEqualTo("txn_1")
        assertThat(event.providerRefundId).isEqualTo("adj_1")
        assertThat(event.amount).isEqualTo(PaymentAmount(499, "USD"))
        assertThat((gateway.interpret(adjustmentEvent(type = "adjustment.updated", status = "approved")) as PushedRefund).status).isEqualTo(PushedRefundStatus.APPROVED)
        assertThat((gateway.interpret(adjustmentEvent(type = "adjustment.updated", status = "rejected")) as PushedRefund).status).isEqualTo(PushedRefundStatus.REJECTED)
        assertThat(gateway.interpret(adjustmentEvent(action = "credit"))).isNull()
        assertThatThrownBy { gateway.parse(WebhookDelivery(mapOf("Paddle-Signature" to sign(created)), created.replace("499", "1").toByteArray())) }.isInstanceOf(PaymentWebhookRejectedException::class.java)
    }

    private fun catchThrowable(block: () -> Any?): Throwable = runCatching { block() }.exceptionOrNull() ?: throw AssertionError("expected a failure")
}
