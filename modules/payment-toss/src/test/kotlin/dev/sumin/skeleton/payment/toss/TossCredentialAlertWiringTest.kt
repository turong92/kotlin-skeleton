package dev.sumin.skeleton.payment.toss

import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentConfirmRequest
import dev.sumin.skeleton.payment.PaymentLookupQuery
import dev.sumin.skeleton.payment.PaymentProviderCredentialAlert
import dev.sumin.skeleton.payment.PaymentProviderException
import dev.sumin.skeleton.payment.toss.support.FakeTossServer
import dev.sumin.skeleton.payment.toss.support.TossFixtures
import dev.sumin.skeleton.payment.toss.support.realHttpClient
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Supplier
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * 배선: 자동 구성이 만든 공급자에 앱의 [PaymentProviderCredentialAlert] 빈이 실제로 닿는다 — 자동 구성 → 공급자 → 두 매퍼(승인 · 조회).
 * 매퍼 단위 시험은 경보 객체를 직접 넣어서 이 연결을 못 잡는다.
 */
class TossCredentialAlertWiringTest {
    private val server = FakeTossServer()
    private val rejected = CopyOnWriteArrayList<String>()

    @AfterTest
    fun tearDown() = server.close()

    @Test
    fun `the alert bean in the context is called once for a 401 on a lookup and once for a 401 on a confirm`() {
        server.on("GET", "/v1/payments/pay_key_1", TossFixtures.error(401, "UNAUTHORIZED_KEY"))
        server.on("POST", "/v1/payments/confirm", TossFixtures.error(401, "UNAUTHORIZED_KEY"))
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TossPaymentAutoConfiguration::class.java))
            .withBean(ExternalHttpClient::class.java, Supplier { realHttpClient() })
            .withBean(PaymentProviderCredentialAlert::class.java, Supplier { PaymentProviderCredentialAlert { rejected += it } })
            .withPropertyValues(
                "skeleton.payment-toss.enabled=true",
                "skeleton.payment-toss.secret-key=${server.secretKey}",
                "skeleton.payment-toss.base-url=${server.baseUrl}",
            )
            .run { context ->
                val provider = context.getBean(TossPaymentProvider::class.java)

                assertFailsWith<PaymentProviderException> { provider.lookup(PaymentLookupQuery(providerPaymentId = "pay_key_1", orderId = null)) }
                assertEquals(listOf("toss"), rejected.toList(), "the lookup mapper reached the bean")

                assertFailsWith<PaymentProviderException> {
                    provider.confirm(PaymentConfirmRequest("pay_key_1", PaymentAmount(3_000, "KRW"), merchantReferenceId = "ord_abcdef123456"))
                }
                assertEquals(listOf("toss", "toss"), rejected.toList(), "the confirm mapper reached the bean")
            }
    }
}
