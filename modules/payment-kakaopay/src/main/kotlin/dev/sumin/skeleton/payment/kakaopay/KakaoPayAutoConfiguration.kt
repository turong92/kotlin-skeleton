package dev.sumin.skeleton.payment.kakaopay

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployGuard
import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.http.OutboundHttpProperties
import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.common.project.ProjectsConfigured
import dev.sumin.skeleton.common.project.ProjectsNotConfigured
import dev.sumin.skeleton.payment.PaymentGatewaySource
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
@EnableConfigurationProperties(KakaoPayProperties::class)
class KakaoPayAutoConfiguration {
    /** 켰어도 시크릿 키나 가맹점 코드가 비면 공급자를 만들지 않는다. 그때 **보호 환경(stage · prod)에서는 가드가 기동을 실패시키고**, 그 밖의 환경에서는 경고만 한다([KakaoPayKeyGuard]). */
    @Bean("kakaoPayProvider")
    @ConditionalOnProperty(prefix = "skeleton.payment-kakaopay", name = ["enabled"], havingValue = "true")
    @Conditional(KakaoPayCredentialsConfigured::class, ProjectsNotConfigured::class)
    @ConditionalOnMissingBean(name = ["kakaoPayProvider"])
    fun kakaoPayProvider(
        httpClient: ExternalHttpClient,
        properties: KakaoPayProperties,
        credentialAlert: ObjectProvider<PaymentProviderCredentialAlert>,
    ): KakaoPayProvider =
        KakaoPayProvider(httpClient = httpClient, properties = properties, credentialAlert = credentialAlert.getIfAvailable { PaymentProviderCredentialAlert.NONE })

    /**
     * 프로젝트 모드: 프로젝트마다 자기 `payment-kakaopay.*` (공통 위에 덮어)로 카카오페이 게이트웨이(= 그 프로젝트의 시크릿 키 · 가맹점 코드를 든 클라이언트)를 만든다.
     * 그 프로젝트에서 켜지 않았거나 시크릿 키 · 가맹점 코드가 비었으면 만들지 않는다 - 그 프로젝트의 결제 화면 · 설정 응답에 카카오페이가 나오지 않는다. 외부 HTTP 풀은 공유하지만 키는 요청마다 이 프로젝트의 것이 실린다.
     * 비밀 키와 짝으로 한 프로젝트 것이어야 하는 값: 가맹점 코드(다른 계정의 코드로 승인하면 거절된다) · 돌아올 주소(공통에서 물려받으면 구매자가 다른 프로젝트 화면으로 돌아간다).
     */
    @Bean
    @ConditionalOnMissingBean(name = ["kakaoPayGatewaySource"])
    @Conditional(ProjectsConfigured::class)
    fun kakaoPayGatewaySource(httpClient: ExternalHttpClient, credentialAlert: ObjectProvider<PaymentProviderCredentialAlert>): PaymentGatewaySource =
        PaymentGatewaySource.of(listOf("payment-kakaopay.secret-key", "payment-kakaopay.cid-secret"), listOf("payment-kakaopay.cid", "payment-kakaopay.return-url")) { settings ->
            val properties = settings.bind("skeleton.payment-kakaopay", KakaoPayProperties::class.java)
            if (!properties.enabled || properties.secretKey.isBlank() || properties.cid.isBlank()) {
                null
            } else {
                // 키 거절 경보는 결제사 응답 스레드(프로젝트 문맥 없음)에서 불린다 - 만들 때 프로젝트를 잡아 두고 그 문맥으로 부른다
                val alert = credentialAlert.getIfAvailable { PaymentProviderCredentialAlert.NONE }
                KakaoPayProvider(httpClient = httpClient, properties = properties, credentialAlert = PaymentProviderCredentialAlert { id -> settings.inProject { alert.rejected(id) } })
            }
        }

    /** 프로젝트 모드의 키 가드: 카카오페이를 켠 프로젝트마다 같은 규칙을 본다 - 메시지 앞에 `project '<id>': ` */
    @Bean
    @ConditionalOnMissingBean(name = ["kakaoPayProjectKeyGuard"])
    @Conditional(ProjectsConfigured::class)
    fun kakaoPayProjectKeyGuard(
        projects: ProjectRegistry,
        applicationContext: ApplicationContext,
        environment: Environment,
        outboundHttp: ObjectProvider<OutboundHttpProperties>,
        paymentProperties: ObjectProvider<PaymentProperties>,
    ): DeployGuard {
        val guards = projects.scopedFrom("skeleton.payment-kakaopay", KakaoPayProperties::class.java) { _, properties ->
            guard(properties, applicationContext, environment, outboundHttp, paymentProperties)
        }
        return object : DeployGuard {
            override val name = "payment-kakaopay"
            override fun problems(context: DeployContext) = perProject { it.problems(context) }
            override fun warnings(context: DeployContext) = perProject { it.warnings(context) }
            private fun perProject(of: (KakaoPayKeyGuard) -> List<String>) =
                projects.active.filter { projects.bind(it, "skeleton.payment-kakaopay", KakaoPayProperties::class.java).enabled }
                    .flatMap { project -> of(guards.of(project)).map { "project '${project.id}': $it" } }
        }
    }

    /** 키를 잘못 끼우는 실수를 기동 때 막는다. 켠 순간부터 선다 - 시크릿 키가 비어도 경고하려고. */
    @Bean
    @ConditionalOnProperty(prefix = "skeleton.payment-kakaopay", name = ["enabled"], havingValue = "true")
    @ConditionalOnMissingBean
    @Conditional(ProjectsNotConfigured::class)
    fun kakaoPayKeyGuard(
        properties: KakaoPayProperties,
        applicationContext: ApplicationContext,
        environment: Environment,
        outboundHttp: ObjectProvider<OutboundHttpProperties>,
        paymentProperties: ObjectProvider<PaymentProperties>,
    ): KakaoPayKeyGuard = guard(properties, applicationContext, environment, outboundHttp, paymentProperties)

    private fun guard(
        properties: KakaoPayProperties,
        applicationContext: ApplicationContext,
        environment: Environment,
        outboundHttp: ObjectProvider<OutboundHttpProperties>,
        paymentProperties: ObjectProvider<PaymentProperties>,
    ): KakaoPayKeyGuard =
        KakaoPayKeyGuard(
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
            // 문자열 키가 아니라 묶인 PaymentProperties 에서 읽는다 - 이름을 고쳐도 가드가 말없이 기본값을 보지 않게
            reconcileFirstDelay = { paymentProperties.getIfAvailable { PaymentProperties() }.reconcile.firstDelay },
            refundSettleDelay = { paymentProperties.getIfAvailable { PaymentProperties() }.refund.settleDelay },
        )

    internal class KakaoPayCredentialsConfigured : SpringBootCondition() {
        override fun getMatchOutcome(context: ConditionContext, metadata: AnnotatedTypeMetadata): ConditionOutcome =
            if (context.environment.getProperty("skeleton.payment-kakaopay.secret-key").isNullOrBlank() || context.environment.getProperty("skeleton.payment-kakaopay.cid").isNullOrBlank()) {
                ConditionOutcome.noMatch("skeleton.payment-kakaopay.secret-key or cid is empty")
            } else {
                ConditionOutcome.match("skeleton.payment-kakaopay.secret-key and cid are set")
            }
    }
}
