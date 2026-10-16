package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.project.ProjectRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

@AutoConfiguration
@EnableConfigurationProperties(PaymentProperties::class)
class PaymentAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun paymentProviderRouter(
        providers: List<PaymentGateway>,
        properties: PaymentProperties,
        projects: ObjectProvider<ProjectRegistry>,
        sources: ObjectProvider<PaymentGatewaySource>,
    ): PaymentProviderRouter {
        // 프로젝트 모드: 결제사 게이트웨이 · 라우팅 설정 · 웹훅 비밀 조각이 프로젝트마다 — 결제사 모듈의 [PaymentGatewaySource] 가 프로젝트의 키로 만든다
        val registry = projects.getIfAvailable() ?: return PaymentProviderRouter(providers = providers, properties = properties)
        return PaymentProviderRouter.forProjects(registry, providers, sources.orderedStream().toList())
    }

    @Bean
    @ConditionalOnMissingBean
    fun paymentService(
        router: PaymentProviderRouter,
        failureHandlers: List<PaymentFailureHandler>,
    ): PaymentService =
        PaymentService(
            router = router,
            failureHandlers = failureHandlers,
        )
}
