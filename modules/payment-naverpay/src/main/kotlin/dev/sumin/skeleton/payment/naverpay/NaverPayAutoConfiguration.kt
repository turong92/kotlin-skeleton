package dev.sumin.skeleton.payment.naverpay

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
import dev.sumin.skeleton.payment.PaymentProviderMismatchAlert
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
@EnableConfigurationProperties(NaverPayProperties::class)
class NaverPayAutoConfiguration {
    /** 켰어도 클라이언트 아이디 · 시크릿 · 체인 아이디가 비면 공급자를 만들지 않는다. 그때 **보호 환경(stage · prod)에서는 가드가 기동을 실패시키고**, 그 밖의 환경에서는 경고만 한다([NaverPayKeyGuard]). */
    @Bean("naverPayProvider")
    @ConditionalOnProperty(prefix = "skeleton.payment-naverpay", name = ["enabled"], havingValue = "true")
    @Conditional(NaverPayCredentialsConfigured::class, ProjectsNotConfigured::class)
    @ConditionalOnMissingBean(name = ["naverPayProvider"])
    fun naverPayProvider(
        httpClient: ExternalHttpClient,
        properties: NaverPayProperties,
        credentialAlert: ObjectProvider<PaymentProviderCredentialAlert>,
        mismatchAlert: ObjectProvider<PaymentProviderMismatchAlert>,
    ): NaverPayProvider =
        NaverPayProvider(
            httpClient = httpClient,
            properties = properties,
            credentialAlert = credentialAlert.getIfAvailable { PaymentProviderCredentialAlert.NONE },
            mismatchAlert = mismatchAlert.getIfAvailable { PaymentProviderMismatchAlert.NONE },
        )

    /**
     * 프로젝트 모드: 프로젝트마다 자기 `payment-naverpay.*` (공통 위에 덮어)로 네이버페이 게이트웨이(= 그 프로젝트의 키를 든 클라이언트)를 만든다.
     * 그 프로젝트에서 켜지 않았거나 클라이언트 아이디 · 시크릿 · 체인 아이디가 비었으면 만들지 않는다 - 그 프로젝트의 결제 화면 · 설정 응답에 네이버페이가 나오지 않는다. 외부 HTTP 풀은 공유하지만 키는 요청마다 이 프로젝트의 것이 실린다.
     * 비밀과 짝으로 한 프로젝트 것이어야 하는 값: 클라이언트 아이디 · 체인 아이디(다른 가맹점의 값으로 승인하면 거절된다) · 돌아올 주소(공통에서 물려받으면 구매자가 다른 프로젝트 화면으로 돌아간다).
     */
    @Bean
    @ConditionalOnMissingBean(name = ["naverPayGatewaySource"])
    @Conditional(ProjectsConfigured::class)
    fun naverPayGatewaySource(
        httpClient: ExternalHttpClient,
        credentialAlert: ObjectProvider<PaymentProviderCredentialAlert>,
        mismatchAlert: ObjectProvider<PaymentProviderMismatchAlert>,
    ): PaymentGatewaySource =
        PaymentGatewaySource.of(listOf("payment-naverpay.client-secret"), listOf("payment-naverpay.client-id", "payment-naverpay.chain-id", "payment-naverpay.return-url")) { settings ->
            val properties = settings.bind("skeleton.payment-naverpay", NaverPayProperties::class.java)
            if (!properties.enabled || properties.clientSecret.isBlank() || properties.clientId.isBlank() || properties.chainId.isBlank()) {
                null
            } else {
                // 키 거절 경보는 결제사 응답 스레드(프로젝트 문맥 없음)에서 불린다 - 만들 때 프로젝트를 잡아 두고 그 문맥으로 부른다
                val alert = credentialAlert.getIfAvailable { PaymentProviderCredentialAlert.NONE }
                val mismatch = mismatchAlert.getIfAvailable { PaymentProviderMismatchAlert.NONE }
                NaverPayProvider(
                    httpClient = httpClient,
                    properties = properties,
                    credentialAlert = PaymentProviderCredentialAlert { id -> settings.inProject { alert.rejected(id) } },
                    mismatchAlert = PaymentProviderMismatchAlert { id, orderId, outcome -> settings.inProject { mismatch.applied(id, orderId, outcome) } },
                )
            }
        }

    /** 프로젝트 모드의 키 가드: 네이버페이를 켠 프로젝트마다 같은 규칙을 본다 - 메시지 앞에 `project '<id>': ` */
    @Bean
    @ConditionalOnMissingBean(name = ["naverPayProjectKeyGuard"])
    @Conditional(ProjectsConfigured::class)
    fun naverPayProjectKeyGuard(
        projects: ProjectRegistry,
        applicationContext: ApplicationContext,
        environment: Environment,
        outboundHttp: ObjectProvider<OutboundHttpProperties>,
        paymentProperties: ObjectProvider<PaymentProperties>,
    ): DeployGuard {
        val guards = projects.scopedFrom("skeleton.payment-naverpay", NaverPayProperties::class.java) { _, properties ->
            guard(properties, applicationContext, environment, outboundHttp, paymentProperties)
        }
        return object : DeployGuard {
            override val name = "payment-naverpay"
            override fun problems(context: DeployContext) = perProject { it.problems(context) }
            override fun warnings(context: DeployContext) = perProject { it.warnings(context) }
            private fun perProject(of: (NaverPayKeyGuard) -> List<String>) =
                projects.active.filter { projects.bind(it, "skeleton.payment-naverpay", NaverPayProperties::class.java).enabled }
                    .flatMap { project -> of(guards.of(project)).map { "project '${project.id}': $it" } }
        }
    }

    /** 키를 잘못 끼우는 실수를 기동 때 막는다. 켠 순간부터 선다 - 시크릿이 비어도 경고하려고. */
    @Bean
    @ConditionalOnProperty(prefix = "skeleton.payment-naverpay", name = ["enabled"], havingValue = "true")
    @ConditionalOnMissingBean
    @Conditional(ProjectsNotConfigured::class)
    fun naverPayKeyGuard(
        properties: NaverPayProperties,
        applicationContext: ApplicationContext,
        environment: Environment,
        outboundHttp: ObjectProvider<OutboundHttpProperties>,
        paymentProperties: ObjectProvider<PaymentProperties>,
    ): NaverPayKeyGuard = guard(properties, applicationContext, environment, outboundHttp, paymentProperties)

    private fun guard(
        properties: NaverPayProperties,
        applicationContext: ApplicationContext,
        environment: Environment,
        outboundHttp: ObjectProvider<OutboundHttpProperties>,
        paymentProperties: ObjectProvider<PaymentProperties>,
    ): NaverPayKeyGuard =
        NaverPayKeyGuard(
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

    internal class NaverPayCredentialsConfigured : SpringBootCondition() {
        override fun getMatchOutcome(context: ConditionContext, metadata: AnnotatedTypeMetadata): ConditionOutcome {
            val missing = listOf("client-id", "client-secret", "chain-id").filter { context.environment.getProperty("skeleton.payment-naverpay.$it").isNullOrBlank() }
            return if (missing.isNotEmpty()) {
                ConditionOutcome.noMatch("skeleton.payment-naverpay.${missing.first()} is empty")
            } else {
                ConditionOutcome.match("skeleton.payment-naverpay client-id, client-secret and chain-id are set")
            }
        }
    }
}
