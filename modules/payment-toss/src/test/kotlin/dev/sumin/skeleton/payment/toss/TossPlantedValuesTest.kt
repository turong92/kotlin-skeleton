package dev.sumin.skeleton.payment.toss

import dev.sumin.skeleton.payment.IdempotencyKey
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentConfirmRequest
import dev.sumin.skeleton.payment.PaymentLookupQuery
import dev.sumin.skeleton.payment.PaymentRefundRequest
import dev.sumin.skeleton.payment.WebhookDelivery
import dev.sumin.skeleton.payment.toss.support.CapturedLogs
import dev.sumin.skeleton.payment.toss.support.FakeTossServer
import dev.sumin.skeleton.payment.toss.support.FakeTossServer.Reply
import dev.sumin.skeleton.payment.toss.support.TossFixtures
import dev.sumin.skeleton.payment.toss.support.tossProvider
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 가짜 토스가 응답에 심은 이름 · 이메일 · 전화 · 전체 카드 번호가 **결과 객체 · 예외(메시지 · 데이터 · 원인) · 캡처한 로그** 어디에도 없다 (스펙 §3 19).
 * 심은 값은 이 모듈이 읽지 않는 칸(`customerName` …)과 오류 본문의 `message` 에 들어 있고, 카드 번호는 `card.number` 에도 가려지지 않은 채 들어 있다
 * — 끝 네 글자만 남고 나머지는 사라져야 한다.
 */
class TossPlantedValuesTest {
    private val server = FakeTossServer()
    private val provider = tossProvider(server)

    @AfterTest
    fun tearDown() = server.close()

    private fun assertNoPlanted(label: String, text: String) {
        TossFixtures.PLANTED.ALL.forEach { planted -> assertTrue(!text.contains(planted), "$label leaked '$planted': ${text.take(400)}") }
    }

    private fun describe(error: Throwable): String {
        val parts = mutableListOf<String>()
        var current: Throwable? = error
        while (current != null) {
            parts += "${current.javaClass.name}: ${current.message}"
            parts += current.stackTraceToString()
            (current as? dev.sumin.skeleton.common.ApplicationException)?.let { parts += it.data.toString() + it.detail }
            (current as? dev.sumin.skeleton.payment.PaymentProviderException)?.let { parts += it.providerError.toString() }
            current = current.cause
        }
        return parts.joinToString("\n")
    }

    @Test
    fun `successful answers - confirm, refund, lookup - carry none of the planted values`() {
        val planted = TossFixtures.payment(
            card = TossFixtures.card(number = TossFixtures.PLANTED.FULL_CARD_NUMBER),
            cancels = listOf(TossFixtures.cancel("tx_1", 1_000)),
            status = "PARTIAL_CANCELED",
        )
        server.on("POST", "/v1/payments/confirm", Reply.Json(200, planted))
        server.on("POST", "/v1/payments/pay_key_1/cancel", Reply.Json(200, planted))
        server.on("GET", "/v1/payments/pay_key_1", Reply.Json(200, planted))

        CapturedLogs().use { logs ->
            val confirm = provider.confirm(PaymentConfirmRequest("pay_key_1", PaymentAmount(3_000, "KRW"), merchantReferenceId = "ord_abcdef123456"))
            val refund = provider.refund(PaymentRefundRequest("pay_key_1", PaymentAmount(1_000, "KRW"), "고객 요청", idempotencyKey = IdempotencyKey("rfd_1")))
            val snapshot = requireNotNull(provider.lookup(PaymentLookupQuery("pay_key_1", null)))

            assertNoPlanted("confirm", confirm.toString())
            assertNoPlanted("refund", refund.toString())
            assertNoPlanted("lookup", snapshot.toString())
            assertTrue(snapshot.card?.last4 == "1234", "only the last four characters survive: ${snapshot.card}")
            assertTrue(logs.lines().any { "payment.toss.confirm" in it }, "the capture is not empty: the platform's completion line is there")
            assertNoPlanted("logs", logs.lines().joinToString("\n"))
        }
    }

    @Test
    fun `error answers - the message the provider put in the body never reaches the exception or the logs`() {
        listOf(
            TossFixtures.error(403, "REJECT_CARD_PAYMENT"),
            TossFixtures.error(400, "PROVIDER_ERROR"),
            TossFixtures.error(500, "FAILED_INTERNAL_SYSTEM_PROCESSING"),
            Reply.Json(502, "<html>${TossFixtures.PLANTED.NAME} ${TossFixtures.PLANTED.EMAIL} ${TossFixtures.PLANTED.PHONE} ${TossFixtures.PLANTED.FULL_CARD_NUMBER}</html>"),
        ).forEach { reply ->
            server.on("POST", "/v1/payments/confirm", reply)
            server.on("POST", "/v1/payments/pay_key_1/cancel", reply)
            server.on("GET", "/v1/payments/pay_key_1", reply)

            CapturedLogs().use { logs ->
                val errors = listOf(
                    runCatching { provider.confirm(PaymentConfirmRequest("pay_key_1", PaymentAmount(3_000, "KRW"), merchantReferenceId = "ord_abcdef123456")) }.exceptionOrNull(),
                    runCatching { provider.refund(PaymentRefundRequest("pay_key_1", PaymentAmount(1_000, "KRW"), "고객 요청", idempotencyKey = IdempotencyKey("rfd_1"))) }.exceptionOrNull(),
                    runCatching { provider.lookup(PaymentLookupQuery("pay_key_1", null)) }.exceptionOrNull(),
                )
                errors.forEach { error ->
                    assertTrue(error != null, "the call should have failed")
                    assertNoPlanted("exception for $reply", describe(error))
                }
                assertNoPlanted("logs for $reply", logs.lines().joinToString("\n"))
            }
        }
    }

    @Test
    fun `broken and dropped answers do not quote the body either`() {
        val broken = TossFixtures.PLANTED.ALL.joinToString(" ", prefix = "{\"customerName\": \"", postfix = "\", \"status\": ")
        server.on("POST", "/v1/payments/confirm", Reply.Broken(body = broken))
        server.on("GET", "/v1/payments/pay_key_1", Reply.Broken(body = broken))

        CapturedLogs().use { logs ->
            val errors = listOf(
                runCatching { provider.confirm(PaymentConfirmRequest("pay_key_1", PaymentAmount(3_000, "KRW"), merchantReferenceId = "ord_abcdef123456")) }.exceptionOrNull(),
                runCatching { provider.lookup(PaymentLookupQuery("pay_key_1", null)) }.exceptionOrNull(),
            )
            errors.forEach { assertNoPlanted("broken answer exception", describe(requireNotNull(it))) }
            assertTrue(logs.lines().any { "could not be read" in it }, "the unreadable-answer warning is logged, class name only")
            assertNoPlanted("logs", logs.lines().joinToString("\n"))
        }
    }

    @Test
    fun `the card object prints without the number - decoders log decoded objects at debug`() {
        val card = TossCard(issuerCode = "61", number = TossFixtures.PLANTED.FULL_CARD_NUMBER)
        val response = TossPaymentResponse(paymentKey = "pay_key_1", card = card)

        assertNoPlanted("card", card.toString())
        assertNoPlanted("response", response.toString())
    }

    @Test
    fun `a webhook body with planted values yields an envelope without them`() {
        val envelope = provider.parse(WebhookDelivery(emptyMap(), TossFixtures.webhook().toByteArray()))

        assertNoPlanted("envelope", envelope.toString())
    }
}
