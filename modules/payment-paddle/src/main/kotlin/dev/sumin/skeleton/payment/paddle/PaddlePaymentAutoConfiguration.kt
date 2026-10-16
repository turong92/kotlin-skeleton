package dev.sumin.skeleton.payment.paddle

import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.project.ProjectsConfigured
import dev.sumin.skeleton.common.project.ProjectsNotConfigured
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.payment.PaymentGatewaySource
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionOutcome
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.SpringBootCondition
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.core.type.AnnotatedTypeMetadata

@AutoConfiguration
@EnableConfigurationProperties(PaddleProperties::class)
class PaddlePaymentAutoConfiguration {
    /** 켰어도 세 비밀 중 하나라도 비면 이 결제사만 서지 않는다 — 서버는 뜬다(다른 결제사는 그대로). */
    @Bean("paddlePaymentProvider")
    @ConditionalOnProperty(prefix = "skeleton.payment-paddle", name = ["enabled"], havingValue = "true")
    @Conditional(PaddleSecretsConfigured::class, ProjectsNotConfigured::class)
    @ConditionalOnMissingBean(name = ["paddlePaymentProvider"])
    fun paddlePaymentProvider(httpClient: ExternalHttpClient, properties: PaddleProperties, time: ObjectProvider<TimeProvider>): PaddlePaymentProvider =
        PaddlePaymentProvider(httpClient, properties, time.getIfAvailable { TimeProvider.systemUtc() })

    /** 프로젝트 모드: 프로젝트마다 자기 `payment-paddle.*` (API 키 · 클라이언트 토큰 · 웹훅 비밀값)로 만든다. 세 비밀 중 하나라도 비었거나 켜지 않은 프로젝트에는 없다. */
    @Bean
    @ConditionalOnMissingBean(name = ["paddleGatewaySource"])
    @Conditional(ProjectsConfigured::class)
    fun paddleGatewaySource(httpClient: ExternalHttpClient, time: ObjectProvider<TimeProvider>): PaymentGatewaySource =
        PaymentGatewaySource.of(listOf("payment-paddle.api-key", "payment-paddle.webhook-secret"), listOf("payment-paddle.client-token")) { settings ->
            val properties = settings.bind("skeleton.payment-paddle", PaddleProperties::class.java)
            if (!properties.enabled || listOf(properties.apiKey, properties.clientToken, properties.webhookSecret).any { it.isBlank() }) {
                null
            } else {
                PaddlePaymentProvider(httpClient, properties, time.getIfAvailable { TimeProvider.systemUtc() })
            }
        }

    internal class PaddleSecretsConfigured : SpringBootCondition() {
        override fun getMatchOutcome(context: ConditionContext, metadata: AnnotatedTypeMetadata): ConditionOutcome {
            val missing = listOf("api-key", "client-token", "webhook-secret").filter { context.environment.getProperty("skeleton.payment-paddle.$it").isNullOrBlank() }
            return if (missing.isEmpty()) ConditionOutcome.match("the Paddle secrets are set") else ConditionOutcome.noMatch("skeleton.payment-paddle.$missing is empty")
        }
    }
}
