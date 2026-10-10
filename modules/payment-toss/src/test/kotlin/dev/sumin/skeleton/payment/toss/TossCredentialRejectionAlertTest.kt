package dev.sumin.skeleton.payment.toss

import dev.sumin.skeleton.payment.PaymentProviderCredentialAlert
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 키가 폐기된 배포는 모든 결제가 「거절」로만 보인다 — 분류(확정 실패)는 그대로 두고 인증 계열 거절에서만 주인 경보 한 줄을 낸다. */
class TossCredentialRejectionAlertTest {
    private val rejected = CopyOnWriteArrayList<String>()
    private val alert = PaymentProviderCredentialAlert { rejected += it }

    private fun map(status: Int, code: String, mapper: TossPaymentErrorMapper = TossPaymentErrorMapper(providerId = "toss", credentialAlert = alert)) =
        mapper.map(providerErrorContext(clientName = "payment-toss", upstreamStatus = status, body = """{"code":"$code","message":"x"}"""))

    @Test
    fun `a 401 key refusal alerts the owner once per response and stays a definitive failure`() {
        listOf(401 to "INVALID_API_KEY", 401 to "UNAUTHORIZED_KEY").forEach { (status, code) ->
            rejected.clear()

            val error = map(status, code).providerError

            assertEquals(listOf("toss"), rejected.toList(), code)
            assertTrue(error.definitive, "$code keeps its classification")
        }
    }

    @Test
    fun `a forbidden request stays a definitive failure but does not alert - it can also mean a payment key and order number that differ from the first request`() {
        val error = map(403, "FORBIDDEN_REQUEST").providerError

        assertTrue(rejected.isEmpty(), rejected.toString())
        assertTrue(error.definitive, "the classification is unchanged")
    }
}
