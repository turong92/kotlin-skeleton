package dev.sumin.skeleton.payment.googleplay

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployGuard
import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.project.ProjectRegistry
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
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.core.type.AnnotatedTypeMetadata

@AutoConfiguration
@EnableConfigurationProperties(GooglePlayProperties::class)
class GooglePlayPaymentAutoConfiguration {
    /** 켰어도 필수 값 중 하나라도 비었거나 서비스 계정을 읽을 수 없으면 이 결제사만 서지 않는다 - **보호 환경(stage · prod)에서는 [GooglePlayGuard] 가 기동을 실패시킨다**. */
    @Bean("googlePlayPaymentProvider")
    @ConditionalOnProperty(prefix = "skeleton.payment-googleplay", name = ["enabled"], havingValue = "true")
    @Conditional(GooglePlayValuesUsable::class, ProjectsNotConfigured::class)
    @ConditionalOnMissingBean(name = ["googlePlayPaymentProvider"])
    fun googlePlayPaymentProvider(httpClient: ExternalHttpClient, properties: GooglePlayProperties, time: ObjectProvider<TimeProvider>): GooglePlayPaymentProvider =
        GooglePlayPaymentProvider(httpClient, properties, time.getIfAvailable { TimeProvider.systemUtc() })

    @Bean("googlePlayGuard")
    @ConditionalOnProperty(prefix = "skeleton.payment-googleplay", name = ["enabled"], havingValue = "true")
    @ConditionalOnMissingBean(name = ["googlePlayGuard"])
    @Conditional(ProjectsNotConfigured::class)
    fun googlePlayGuard(properties: GooglePlayProperties): DeployGuard = GooglePlayGuard(properties)

    /** 프로젝트 모드: 프로젝트마다 자기 `payment-googleplay.*` 로 만든다. 값이 비었거나 켜지 않은 프로젝트에는 없다. */
    @Bean
    @ConditionalOnMissingBean(name = ["googlePlayGatewaySource"])
    @Conditional(ProjectsConfigured::class)
    fun googlePlayGatewaySource(httpClient: ExternalHttpClient, time: ObjectProvider<TimeProvider>): PaymentGatewaySource =
        PaymentGatewaySource.of(
            listOf("payment-googleplay.service-account"),
            listOf("payment-googleplay.package-name", "payment-googleplay.pubsub.audience", "payment-googleplay.pubsub.service-account-email"),
        ) { settings ->
            val properties = settings.bind("skeleton.payment-googleplay", GooglePlayProperties::class.java)
            if (!properties.enabled || GooglePlayConfigCheck.problems(properties).isNotEmpty()) null
            else GooglePlayPaymentProvider(httpClient, properties, time.getIfAvailable { TimeProvider.systemUtc() })
        }

    /** 프로젝트 모드의 가드: Google Play 를 켠 프로젝트마다 같은 규칙을 본다 - 메시지 앞에 `project '<id>': ` */
    @Bean("googlePlayProjectGuard")
    @ConditionalOnMissingBean(name = ["googlePlayProjectGuard"])
    @Conditional(ProjectsConfigured::class)
    fun googlePlayProjectGuard(projects: ProjectRegistry): DeployGuard = object : DeployGuard {
        override val name = "payment-googleplay"
        override fun problems(context: DeployContext) = perProject { it.problems(context) }
        override fun warnings(context: DeployContext) = perProject { it.warnings(context) }
        private fun perProject(of: (GooglePlayGuard) -> List<String>) =
            projects.active.map { it to projects.bind(it, GooglePlayProperties.PREFIX, GooglePlayProperties::class.java) }.filter { it.second.enabled }
                .flatMap { (project, properties) -> of(GooglePlayGuard(properties)).map { "project '${project.id}': $it" } }
    }

    internal class GooglePlayValuesUsable : SpringBootCondition() {
        override fun getMatchOutcome(context: ConditionContext, metadata: AnnotatedTypeMetadata): ConditionOutcome {
            val properties = Binder.get(context.environment).bind("skeleton.payment-googleplay", GooglePlayProperties::class.java).orElse(null)
                ?: return ConditionOutcome.noMatch("skeleton.payment-googleplay is not configured")
            val problems = GooglePlayConfigCheck.problems(properties)
            return if (problems.isEmpty()) ConditionOutcome.match("the Google Play values are set") else ConditionOutcome.noMatch("skeleton.payment-googleplay: ${problems.joinToString("; ")}")
        }
    }
}
