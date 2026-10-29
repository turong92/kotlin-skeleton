package dev.sumin.skeleton.payment.paddle

import dev.sumin.skeleton.payment.HostedPlanApplied
import dev.sumin.skeleton.payment.HostedPlanChange
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentDeclinedException
import dev.sumin.skeleton.payment.PaymentPlanChangeNotAllowedException
import dev.sumin.skeleton.payment.PaymentProviderException
import dev.sumin.skeleton.payment.PaymentUnavailableException
import dev.sumin.skeleton.payment.PlanChangeKind
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Paddle 구독을 올리는 길 - 가짜 Paddle API 로. 금액은 Paddle 이 계산한 대로 읽고, 막히는 때와 모름을 가르는 곳이 깨지면 장부가 거짓 플랜을 적는다. */
class PaddlePlanChangeTest {
    private val up = HostedPlanChange("sub_1", "basic", PlanChangeKind.IMMEDIATE_SAME_PERIOD)

    @Test
    fun `an upgrade previews prorated immediately with the single target price and reads the charge after credit, the credit and the recurring total`() {
        FakePaddleApi().use { api ->
            api.on("PATCH", "/subscriptions/sub_1/preview", 200, planChangeBody())

            val quote = gateway(api.baseUrl).previewPlanChange(up)

            assertThat(quote.charge).isEqualTo(PaymentAmount(500, "USD"))
            assertThat(quote.credit).isEqualTo(PaymentAmount(300, "USD"))
            assertThat(quote.recurring).isEqualTo(PaymentAmount(1990, "USD"))
            assertThat(quote.periodStart).isEqualTo(Instant.parse("2026-10-13T09:59:00Z"))
            assertThat(quote.periodEnd).isEqualTo(Instant.parse("2026-11-13T09:59:00Z"))
            assertThat(quote.restartsPeriod).isFalse()
            val request = api.requests.single()
            assertThat(request.auth).isEqualTo("Bearer pdl_sdbx_apikey_x")
            assertThat(request.body).contains("\"price_id\":\"pri_basic\"").contains("\"quantity\":1").contains("\"proration_billing_mode\":\"prorated_immediately\"").doesNotContain("on_payment_failure")
        }
    }

    @Test
    fun `a billing frequency change is prorated immediately too and the period restarts, an apply patches the subscription itself`() {
        FakePaddleApi().use { api ->
            api.on("PATCH", "/subscriptions/sub_1", 200, """{"data":{"id":"sub_1","status":"active","items":[{"price":{"id":"pri_basic"},"quantity":1}]}}""") // 실측: 적용 응답에는 금액이 없다
            api.on("PATCH", "/subscriptions/sub_1/preview", 200, planChangeBody(credit = null))
            val provider = gateway(api.baseUrl)

            assertThat(provider.previewPlanChange(up.copy(kind = PlanChangeKind.IMMEDIATE_NEW_PERIOD)).restartsPeriod).isTrue()
            provider.applyPlanChange(up.copy(kind = PlanChangeKind.IMMEDIATE_NEW_PERIOD))
            assertThat(api.requests.last().path).isEqualTo("/subscriptions/sub_1")
            assertThat(api.requests.last().body).contains("prorated_immediately")
        }
    }

    @Test
    fun `a change below the provider minimum is asked again with do not bill and quotes zero, in the preview and in the apply`() {
        FakePaddleApi().use { api ->
            for ((method, path) in listOf("PATCH" to "/subscriptions/sub_1/preview", "PATCH" to "/subscriptions/sub_1")) {
                api.onNext(method, path, 400, paddleError("subscription_update_transaction_balance_less_than_charge_limit"))
                api.onNext(method, path, 200, planChangeBody(grandTotal = null, credit = null)) // 적용 응답은 본문의 금액을 읽지 않는다
            }
            val provider = gateway(api.baseUrl)

            val preview = provider.previewPlanChange(up)
            provider.applyPlanChange(up)

            assertThat(preview.charge).isEqualTo(PaymentAmount(0, "USD"))
            assertThat(api.requests.map { it.body.contains("do_not_bill") }).containsExactly(false, true, false, true)
        }
    }

    @Test
    fun `a declined payment is a decline, a renewal lock or past due is not allowed, a server error is unknown`() {
        FakePaddleApi().use { api ->
            val provider = gateway(api.baseUrl)
            fun answer(status: Int, code: String) = api.on("PATCH", "/subscriptions/sub_1", status, paddleError(code))

            answer(400, "subscription_payment_declined")
            assertThatThrownBy { provider.applyPlanChange(up) }.isInstanceOfSatisfying(PaymentDeclinedException::class.java) { assertThat(it.providerCode).isEqualTo("subscription_payment_declined") }

            listOf("subscription_locked_renewal", "subscription_update_when_past_due", "subscription_locked_pending_changes", "subscription_immediate_charge_hour_limit_exceeded").forEach {
                answer(409, it)
                assertThatThrownBy { provider.applyPlanChange(up) }.isInstanceOf(PaymentPlanChangeNotAllowedException::class.java)
            }

            answer(403, "forbidden")
            assertThatThrownBy { provider.applyPlanChange(up) }.isInstanceOfSatisfying(PaymentProviderException::class.java) { assertThat(it.providerError.definitive).isTrue() }

            answer(500, "internal_error")
            assertThatThrownBy { provider.applyPlanChange(up) }.isInstanceOfSatisfying(PaymentProviderException::class.java) { assertThat(it.providerError.definitive).isFalse() }
        }
    }

    @Test
    fun `a target plan without a price id is unavailable and no request is sent`() {
        FakePaddleApi().use { api ->
            val provider = gateway(api.baseUrl)

            assertThatThrownBy { provider.previewPlanChange(up.copy(targetItemRef = "unpriced")) }.isInstanceOf(PaymentUnavailableException::class.java)
            assertThatThrownBy { provider.applyPlanChange(up.copy(targetItemRef = "unpriced")) }.isInstanceOf(PaymentUnavailableException::class.java)
            assertThat(api.requests).isEmpty()
        }
    }

    @Test
    fun `the real apply answer carries no amounts, only the items are checked, and an answer with another price is unknown`() {
        FakePaddleApi().use { api ->
            val provider = gateway(api.baseUrl)
            api.on("PATCH", "/subscriptions/sub_1", 200, """{"data":{"id":"sub_1","status":"active","currency_code":"USD","items":[{"price":{"id":"pri_basic"},"quantity":1}],"immediate_transaction":null,"next_transaction":null,"update_summary":null,"recurring_transaction_details":null}}""")
            assertThat(provider.applyPlanChange(up)).isEqualTo(HostedPlanApplied())

            api.on("PATCH", "/subscriptions/sub_1", 200, """{"data":{"id":"sub_1","current_billing_period":{"starts_at":"2026-10-29T02:00:00Z","ends_at":"2027-10-29T02:00:00Z"},"items":[{"price":{"id":"pri_basic"},"quantity":1}]}}""")
            assertThat(provider.applyPlanChange(up)).isEqualTo(HostedPlanApplied(Instant.parse("2026-10-29T02:00:00Z"), Instant.parse("2027-10-29T02:00:00Z")))

            api.on("PATCH", "/subscriptions/sub_1", 200, """{"data":{"id":"sub_1","items":[{"price":{"id":"pri_cheer"},"quantity":1}]}}""")
            assertThatThrownBy { provider.applyPlanChange(up) }.isInstanceOf(PaymentProviderException::class.java)
        }
    }

    @Test
    fun `a preview without an immediate transaction is zero only when the provider does not bill, never when it says it charges`() {
        FakePaddleApi().use { api ->
            val provider = gateway(api.baseUrl)
            val noImmediate = """{"data":{"currency_code":"USD","current_billing_period":{"starts_at":"2026-10-13T09:59:00Z","ends_at":"2026-11-13T09:59:00Z"},"immediate_transaction":null,"recurring_transaction_details":{"totals":{"total":"1990"}},"update_summary":%s}}"""
            api.on("PATCH", "/subscriptions/sub_1/preview", 200, noImmediate.format("""{"result":{"action":"charge","amount":"148"}}"""))
            assertThatThrownBy { provider.previewPlanChange(up) }.isInstanceOf(PaymentProviderException::class.java)

            api.on("PATCH", "/subscriptions/sub_1/preview", 200, noImmediate.format("null")) // 같은 품목 - 아무 일도 없다
            assertThat(provider.previewPlanChange(up).charge).isEqualTo(PaymentAmount(0, "USD"))

            api.onNext("PATCH", "/subscriptions/sub_1/preview", 400, paddleError("subscription_update_transaction_balance_less_than_charge_limit"))
            api.on("PATCH", "/subscriptions/sub_1/preview", 200, noImmediate.format("""{"result":{"action":"charge","amount":"148"}}"""))
            assertThat(provider.previewPlanChange(up).charge).isEqualTo(PaymentAmount(0, "USD")) // do_not_bill 로 다시 물었다
        }
    }
}
