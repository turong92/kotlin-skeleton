package dev.sumin.skeleton.payment.kakaopay

import dev.sumin.skeleton.common.http.OutboundHttpAutoConfiguration
import dev.sumin.skeleton.payment.PaymentAutoConfiguration
import dev.sumin.skeleton.payment.PaymentBillingGateway
import dev.sumin.skeleton.payment.PaymentCheckoutGateway
import dev.sumin.skeleton.payment.PaymentConfirmGateway
import dev.sumin.skeleton.payment.PaymentLookupGateway
import dev.sumin.skeleton.payment.PaymentProviderRouter
import dev.sumin.skeleton.payment.PaymentWebhookGateway
import dev.sumin.skeleton.payment.testing.ScriptedGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** 조립: 설정이 없으면 뜬다 · 켜면 한 빈이 모든 능력을 갖고(웹훅만 없다) · 비밀이 비면 제공자 빈만 없고 · 꺼져 있으면 이미 있는 결제사는 그대로다. */
class KakaoPayWiringTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(OutboundHttpAutoConfiguration::class.java, PaymentAutoConfiguration::class.java, KakaoPayAutoConfiguration::class.java))

    @Test
    fun `boots with no configuration, with no provider bean and no guard`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean("kakaoPayProvider")
            assertThat(context).doesNotHaveBean(KakaoPayKeyGuard::class.java)
        }
    }

    @Test
    fun `switched on, one provider bean answers for the checkout, the approve and the lookup - and there is no webhook capability`() {
        runner.withPropertyValues("skeleton.payment-kakaopay.enabled=true", "skeleton.payment-kakaopay.secret-key=k", "skeleton.payment-kakaopay.cid=TC0ONETIME", "skeleton.payment-kakaopay.return-url=https://x.test/r")
            .run { context ->
                assertThat(context).hasNotFailed()
                val provider = context.getBean(KakaoPayProvider::class.java)
                val router = context.getBean(PaymentProviderRouter::class.java)
                assertThat(router.gateway("kakaopay", PaymentLookupGateway::class)).isSameAs(provider)
                assertThat(router.gateway("kakaopay", PaymentCheckoutGateway::class)).isSameAs(provider)
                assertThat(router.gateway("kakaopay", PaymentConfirmGateway::class)).isSameAs(provider)
                assertThat(router.providerIds(PaymentWebhookGateway::class)).doesNotContain("kakaopay")
                assertThat(context).hasSingleBean(KakaoPayKeyGuard::class.java)
            }
    }

    @Test
    fun `switched on without the secret key or the merchant code, only the provider bean is missing (the guard stands)`() {
        runner.withPropertyValues("skeleton.payment-kakaopay.enabled=true", "skeleton.payment-kakaopay.secret-key=k").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean("kakaoPayProvider")
            assertThat(context).hasSingleBean(KakaoPayKeyGuard::class.java)
        }
    }

    @Test
    fun `with the module on the classpath but off, a payment provider that is already there works as before`() {
        val scripted = ScriptedGateway()
        runner.withBean(ScriptedGateway::class.java, java.util.function.Supplier { scripted }).run { context ->
            assertThat(context).hasNotFailed()
            val router = context.getBean(PaymentProviderRouter::class.java)
            assertThat(router.gateway("scripted", PaymentConfirmGateway::class)).isSameAs(scripted)
            assertThat(router.providerIds(PaymentCheckoutGateway::class)).containsExactly("scripted")
            assertThat(context.getBeansOfType(KakaoPayProvider::class.java)).isEmpty()
        }
    }

    @Test
    fun `the subscription capability appears only with a subscription merchant code, on the same single bean`() {
        val base = arrayOf("skeleton.payment-kakaopay.enabled=true", "skeleton.payment-kakaopay.secret-key=k", "skeleton.payment-kakaopay.cid=TC0ONETIME", "skeleton.payment-kakaopay.return-url=https://x.test/r")
        runner.withPropertyValues(*base).run { context ->
            assertThat(context.getBean(PaymentProviderRouter::class.java).providerIds(PaymentBillingGateway::class)).doesNotContain("kakaopay")
        }
        runner.withPropertyValues(*base, "skeleton.payment-kakaopay.subscription-cid=TCSUBSCRIP").run { context ->
            assertThat(context).hasNotFailed()
            val router = context.getBean(PaymentProviderRouter::class.java)
            assertThat(router.gateway("kakaopay", PaymentBillingGateway::class)).isSameAs(context.getBean(KakaoPayProvider::class.java))
            assertThat(router.gateway("kakaopay", PaymentConfirmGateway::class)).isSameAs(router.gateway("kakaopay", PaymentBillingGateway::class))
        }
    }
}
