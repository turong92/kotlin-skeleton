package dev.sumin.skeleton.payment.toss

import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.http.OutboundHttpProperties
import dev.sumin.skeleton.payment.PaymentProperties
import dev.sumin.skeleton.payment.PaymentProviderCredentialAlert
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionOutcome
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.SpringBootCondition
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.core.env.Environment
import org.springframework.core.type.AnnotatedTypeMetadata

@AutoConfiguration
@EnableConfigurationProperties(TossPaymentProperties::class)
class TossPaymentAutoConfiguration {
    /** 켰어도 시크릿 키가 비면 공급자를 만들지 않는다. 그때 **보호 환경(stage · prod)에서는 가드가 기동을 실패시키고**, 그 밖의 환경에서는 경고만 한다([TossKeyGuard]). */
    @Bean("tossPaymentProvider")
    @ConditionalOnProperty(
        prefix = "skeleton.payment-toss",
        name = ["enabled"],
        havingValue = "true",
    )
    @Conditional(TossSecretKeyConfigured::class)
    @ConditionalOnMissingBean(name = ["tossPaymentProvider"])
    fun tossPaymentProvider(
        httpClient: ExternalHttpClient,
        properties: TossPaymentProperties,
        credentialAlert: ObjectProvider<PaymentProviderCredentialAlert>,
    ): TossPaymentProvider =
        TossPaymentProvider(httpClient = httpClient, properties = properties, credentialAlert = credentialAlert.getIfAvailable { PaymentProviderCredentialAlert.NONE })

    /** 키를 잘못 끼우는 실수를 기동 때 막는다(스펙 §9.3). 켠 순간부터 선다 — 시크릿 키가 비어도 경고하려고. */
    @Bean
    @ConditionalOnProperty(
        prefix = "skeleton.payment-toss",
        name = ["enabled"],
        havingValue = "true",
    )
    @ConditionalOnMissingBean
    fun tossKeyGuard(
        properties: TossPaymentProperties,
        applicationContext: ApplicationContext,
        environment: Environment,
        outboundHttp: ObjectProvider<OutboundHttpProperties>,
        paymentProperties: ObjectProvider<PaymentProperties>,
    ): TossKeyGuard =
        TossKeyGuard(
            properties = properties,
            ledgerPresent = { LedgerPresence.present(applicationContext) },
            jobQueueWorkerRunning = {
                environment.getProperty("skeleton.job-queue.enabled", Boolean::class.java, true) &&
                    environment.getProperty("skeleton.job-queue.worker-enabled", Boolean::class.java, true)
            },
            responseTimeout = {
                val outbound = outboundHttp.getIfAvailable { OutboundHttpProperties() }
                outbound.clients[properties.clientName]?.responseTimeout ?: outbound.defaultResponseTimeout
            },
            // 문자열 키가 아니라 묶인 PaymentProperties 에서 읽는다 — 이름을 고쳐도 가드가 말없이 기본값을 보지 않게
            reconcileFirstDelay = { paymentProperties.getIfAvailable { PaymentProperties() }.reconcile.firstDelay },
            refundSettleDelay = { paymentProperties.getIfAvailable { PaymentProperties() }.refund.settleDelay },
        )

    internal class TossSecretKeyConfigured : SpringBootCondition() {
        override fun getMatchOutcome(context: ConditionContext, metadata: AnnotatedTypeMetadata): ConditionOutcome =
            if (context.environment.getProperty("skeleton.payment-toss.secret-key").isNullOrBlank()) {
                ConditionOutcome.noMatch("skeleton.payment-toss.secret-key is empty")
            } else {
                ConditionOutcome.match("skeleton.payment-toss.secret-key is set")
            }
    }
}
