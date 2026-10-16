package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployGuard
import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.common.project.ProjectsConfigured
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional

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

    /**
     * 프로젝트 모드에서 앱이 직접 둔 [PaymentGateway] 빈은 모든 프로젝트가 같이 쓴다(한 계정의 키가 모든 프로젝트 주문에) — 보호 환경(stage · prod)에서는 기동 실패, 그 밖은 경고.
     * 결제사 모듈의 게이트웨이는 [PaymentGatewaySource] 로 프로젝트마다 만들어지므로 여기 걸리지 않는다.
     */
    @Bean
    @ConditionalOnMissingBean(name = ["paymentSharedGatewaysDeployGuard"])
    @Conditional(ProjectsConfigured::class)
    fun paymentSharedGatewaysDeployGuard(providers: List<PaymentGateway>): DeployGuard = object : DeployGuard {
        override val name = "payment"
        override fun problems(context: DeployContext) = if (context.protectedEnv) findings() else emptyList()
        override fun warnings(context: DeployContext) = if (context.protectedEnv) emptyList() else findings()
        private fun findings() = if (providers.isEmpty()) emptyList() else listOf(
            "PaymentGateway bean(s) ${providers.joinToString { "'${it.providerId}'" }} defined by the app are shared by every project (one provider account and key for all projects' orders); " +
                "provide a PaymentGatewaySource instead so each project gets its own",
        )
    }
}
