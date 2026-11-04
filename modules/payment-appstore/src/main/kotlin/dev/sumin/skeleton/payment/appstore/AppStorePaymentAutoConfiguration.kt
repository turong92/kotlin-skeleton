package dev.sumin.skeleton.payment.appstore

import dev.sumin.skeleton.common.deploy.DeployGuard
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
@EnableConfigurationProperties(AppStoreProperties::class)
class AppStorePaymentAutoConfiguration {
    /**
     * 애플 서명을 확인하는 빈. **신뢰 루트(Apple Root CA - G3)를 바꾸는 설정은 없다** - 시험만 이 빈을 바꿔 시험용 루트를 넣는다.
     */
    @Bean
    @ConditionalOnProperty(prefix = "skeleton.payment-appstore", name = ["enabled"], havingValue = "true")
    @ConditionalOnMissingBean
    fun appStoreSignatureVerifier(time: ObjectProvider<TimeProvider>): AppStoreSignatureVerifier =
        AppStoreSignatureVerifier(time.getIfAvailable { TimeProvider.systemUtc() })

    /** 켰어도 필수 값 중 하나라도 비면 이 결제사만 서지 않는다 - **보호 환경(stage · prod)에서는 [AppStoreGuard] 가 기동을 실패시킨다**. */
    @Bean("appStorePaymentProvider")
    @ConditionalOnProperty(prefix = "skeleton.payment-appstore", name = ["enabled"], havingValue = "true")
    @Conditional(AppStoreValuesConfigured::class, ProjectsNotConfigured::class)
    @ConditionalOnMissingBean(name = ["appStorePaymentProvider"])
    fun appStorePaymentProvider(httpClient: ExternalHttpClient, properties: AppStoreProperties, verifier: AppStoreSignatureVerifier, time: ObjectProvider<TimeProvider>): AppStorePaymentProvider =
        AppStorePaymentProvider(httpClient, properties, verifier, time.getIfAvailable { TimeProvider.systemUtc() })

    @Bean("appStoreGuard")
    @ConditionalOnProperty(prefix = "skeleton.payment-appstore", name = ["enabled"], havingValue = "true")
    @ConditionalOnMissingBean(name = ["appStoreGuard"])
    @Conditional(ProjectsNotConfigured::class)
    fun appStoreGuard(properties: AppStoreProperties): DeployGuard = AppStoreGuard(properties)

    /** 프로젝트 모드: 프로젝트마다 자기 `payment-appstore.*` 로 만든다. 값이 비었거나 켜지 않은 프로젝트에는 없다. */
    @Bean
    @ConditionalOnMissingBean(name = ["appStoreGatewaySource"])
    @Conditional(ProjectsConfigured::class)
    fun appStoreGatewaySource(httpClient: ExternalHttpClient, verifier: ObjectProvider<AppStoreSignatureVerifier>, time: ObjectProvider<TimeProvider>): PaymentGatewaySource =
        PaymentGatewaySource.of(listOf("payment-appstore.private-key"), listOf("payment-appstore.bundle-id", "payment-appstore.app-apple-id", "payment-appstore.issuer-id", "payment-appstore.key-id")) { settings ->
            val properties = settings.bind("skeleton.payment-appstore", AppStoreProperties::class.java)
            if (!properties.enabled || properties.missing().isNotEmpty()) {
                null
            } else {
                val now = time.getIfAvailable { TimeProvider.systemUtc() }
                AppStorePaymentProvider(httpClient, properties, verifier.getIfAvailable { AppStoreSignatureVerifier(now) }, now)
            }
        }

    internal class AppStoreValuesConfigured : SpringBootCondition() {
        override fun getMatchOutcome(context: ConditionContext, metadata: AnnotatedTypeMetadata): ConditionOutcome {
            val missing = listOf("bundle-id", "app-apple-id", "issuer-id", "key-id", "private-key").filter { context.environment.getProperty("skeleton.payment-appstore.$it").isNullOrBlank() }
            return if (missing.isEmpty()) ConditionOutcome.match("the App Store values are set") else ConditionOutcome.noMatch("skeleton.payment-appstore.$missing is empty")
        }
    }
}
