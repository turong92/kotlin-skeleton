package dev.sumin.skeleton.payment.toss

import dev.sumin.skeleton.payment.BillingChargeRequest
import dev.sumin.skeleton.payment.IdempotencyKey
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentFailureOutcome
import dev.sumin.skeleton.payment.PaymentFailures
import dev.sumin.skeleton.payment.PaymentOperationStatus
import dev.sumin.skeleton.payment.toss.support.CapturedLogs
import dev.sumin.skeleton.payment.toss.support.FakeTossServer
import dev.sumin.skeleton.payment.toss.support.FakeTossServer.Reply
import dev.sumin.skeleton.payment.toss.support.TossFixtures
import dev.sumin.skeleton.payment.toss.support.tossProvider
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import tools.jackson.databind.json.JsonMapper

/** 자동결제(빌링) — 가짜 토스 서버로 요청 모양과 결과를 본다. 빌링키는 비밀이라 결과 · 예외 · 로그 어디에도 평문이 나오지 않아야 한다. */
class TossBillingHttpTest {
    private val server = FakeTossServer(requireIdempotencyKey = false)
    private val provider = tossProvider(server)
    private val json = JsonMapper.builder().build()
    private val customerKey = "cus_0123456789abcdef0123456789abcdef"
    private val token = "bkey_SECRET_token_0123456789"

    @AfterTest
    fun tearDown() = server.close()

    private fun billingKeyBody(key: String = token) =
        TossFixtures.json(
            mapOf(
                "billingKey" to key,
                "customerKey" to customerKey,
                "authenticatedAt" to "2026-10-09T10:00:00+09:00",
                "card" to TossFixtures.card(number = TossFixtures.PLANTED.FULL_CARD_NUMBER),
                "customerName" to TossFixtures.PLANTED.NAME,
            ),
        )

    private fun chargeRequest(key: String = "ord_sub_0001") =
        BillingChargeRequest(token, customerKey, key, "월 구독", PaymentAmount(3_900, "KRW"), IdempotencyKey(key))

    @Test
    fun `issue exchanges the auth key for a token and keeps only the last four card characters`() {
        server.on("POST", "/v1/billing/authorizations/issue", Reply.Json(200, billingKeyBody()))

        val issued = provider.issue(customerKey, mapOf("authKey" to "auth_key_1"))

        val request = server.requests.single()
        assertEquals(mapOf("authKey" to "auth_key_1", "customerKey" to customerKey), json.readValue(request.body, Map::class.java))
        assertEquals(token, issued.token)
        assertEquals("1234", issued.card?.last4)
        assertEquals("HYUNDAI", issued.card?.issuer)
        TossFixtures.PLANTED.ALL.forEach { assertFalse(issued.toString().contains(it), "leaked $it") }
        assertFalse(issued.toString().contains(token), "the token is secret")
        assertFailsWith<TossIdentifierRejectedException> { provider.issue(customerKey, mapOf("authKey" to "../x?y")) }
        listOf("a/b", "a?b", "").forEach { bad -> assertFailsWith<TossIdentifierRejectedException> { provider.charge(chargeRequest().copy(token = bad)) } }
        assertEquals(1, server.requests.size, "only the first issue reached the provider")
    }

    @Test
    fun `charge posts to the billing key with the order id as the idempotency key and reads the payment like a confirm`() {
        server.on("POST", "/v1/billing/$token", Reply.Json(200, TossFixtures.payment(paymentKey = "pay_sub_1", orderId = "ord_sub_0001", total = 3_900, lastTransactionKey = "tx_1")))

        val result = provider.charge(chargeRequest())

        val request = server.requests.single()
        assertEquals("/v1/billing/$token", request.path)
        assertEquals("ord_sub_0001", request.header("idempotency-key"))
        assertEquals(
            mapOf("customerKey" to customerKey, "amount" to 3900, "orderId" to "ord_sub_0001", "orderName" to "월 구독"),
            json.readValue(request.body, Map::class.java),
        )
        assertEquals(PaymentOperationStatus.CONFIRMED, result.status)
        assertEquals("pay_sub_1", result.providerPaymentId)
        assertEquals(PaymentAmount(3_900, "KRW"), result.amount)
    }

    @Test
    fun `charge refusals the provider documents as no-money are definitive, anything else is unknown, and the call is sent once`() {
        listOf(403 to "REJECT_CARD_COMPANY", 400 to "INVALID_BILL_KEY_REQUEST", 404 to "NOT_FOUND_BILLING").forEach { (status, code) ->
            server.clearRequests()
            server.on("POST", "/v1/billing/$token", TossFixtures.error(status, code))
            val failure = assertFailsWith<Exception> { provider.charge(chargeRequest()) }
            assertEquals(PaymentFailureOutcome.DEFINITIVE, PaymentFailures.outcomeOf(failure), "$status $code")
            assertEquals(1, server.requests.size)
        }
        server.on("POST", "/v1/billing/$token", TossFixtures.error(500, "FAILED_INTERNAL_SYSTEM_PROCESSING"))
        assertEquals(PaymentFailureOutcome.UNKNOWN, PaymentFailures.outcomeOf(assertFailsWith<Exception> { provider.charge(chargeRequest()) }))
        server.on("POST", "/v1/billing/$token", Reply.Broken())
        assertEquals(PaymentFailureOutcome.UNKNOWN, PaymentFailures.outcomeOf(assertFailsWith<Exception> { provider.charge(chargeRequest()) }))
    }

    @Test
    fun `the billing key never reaches an exception, a request description or a log line`() {
        server.on("POST", "/v1/billing/$token", TossFixtures.error(403, "REJECT_CARD_COMPANY"))
        server.on("POST", "/v1/billing/authorizations/issue", Reply.Json(200, billingKeyBody()))

        CapturedLogs().use { logs ->
            val failure = assertFailsWith<Exception> { provider.charge(chargeRequest()) }
            provider.issue(customerKey, mapOf("authKey" to "auth_key_1")).toString()
            val everything = buildList {
                var current: Throwable? = failure
                while (current != null) {
                    add("${current.javaClass.name}: ${current.message}")
                    (current as? dev.sumin.skeleton.common.ApplicationException)?.let { add(it.data.toString() + it.detail) }
                    (current as? dev.sumin.skeleton.payment.PaymentProviderException)?.let { add(it.providerError.toString()) }
                    current = current.cause
                }
                add(chargeRequest().toString())
                addAll(logs.lines())
            }.joinToString("\n")

            assertFalse(everything.contains(token), "the billing key leaked: ${everything.take(600)}")
            assertFalse(everything.contains(customerKey), "the customer key leaked")
            TossFixtures.PLANTED.ALL.forEach { assertFalse(everything.contains(it), "leaked $it") }
        }
    }

    @Test
    fun `revoke deletes the billing key and treats a key the provider no longer knows as already gone`() {
        server.on("DELETE", "/v1/billing/$token", Reply.Json(200, "{}"))
        provider.revoke(customerKey, token)
        assertEquals("DELETE", server.requests.single().method)

        server.on("DELETE", "/v1/billing/$token", TossFixtures.error(400, "INVALID_BILL_KEY_REQUEST"))
        provider.revoke(customerKey, token)

        server.on("DELETE", "/v1/billing/$token", TossFixtures.error(500, "FAILED_INTERNAL_SYSTEM_PROCESSING"))
        assertFailsWith<Exception> { provider.revoke(customerKey, token) }
    }
}
