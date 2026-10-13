package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.deploy.DeployGuard
import dev.sumin.skeleton.common.web.PublicEndpointContributor
import dev.sumin.skeleton.payment.testing.CheerCatalog
import dev.sumin.skeleton.payment.testing.InMemoryPaymentLedgerStore
import dev.sumin.skeleton.payment.testing.InMemoryPaymentTasks
import dev.sumin.skeleton.payment.testing.ScriptedGateway
import dev.sumin.skeleton.payment.web.PaymentWebhookController
import dev.sumin.skeleton.payment.web.PaymentWebhookWebAutoConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 웹훅 수신함의 빈은 **장부(`PaymentLedgerStore`)가 있을 때만** 선다(스펙 §4.3) — 장부가 없으면 수신함 · 처리기 · 컨트롤러 · 공개 경로 · 가드가 하나도 없다.
 * 장부가 있으면 수신함과 처리기는 늘 서고(운영자의 다시 처리), 경로 · 가드는 `webhook.enabled` 를 따르며 경로는 서블릿 웹 앱일 때만이다.
 */
class PaymentWebhookAutoConfigurationTest {
    private val gateway = ScriptedGateway()
    private val configs = AutoConfigurations.of(PaymentAutoConfiguration::class.java, PaymentLedgerAutoConfiguration::class.java, PaymentWebhookWebAutoConfiguration::class.java)

    @Configuration(proxyBeanMethods = false)
    class Ledger {
        @Bean fun store() = InMemoryPaymentLedgerStore()

        @Bean fun tasks(store: InMemoryPaymentLedgerStore): PaymentTasks = InMemoryPaymentTasks(store)
    }

    private val webWithoutLedger = WebApplicationContextRunner().withBean(ScriptedGateway::class.java, java.util.function.Supplier { gateway }).withConfiguration(configs)
    private val web = webWithoutLedger.withUserConfiguration(Ledger::class.java).withBean(PaymentCatalog::class.java, java.util.function.Supplier { CheerCatalog() })

    private val webhookTypes = listOf(PaymentWebhookInbox::class, PaymentWebhookTaskHandler::class, PaymentWebhookController::class, PaymentWebhookDeployGuard::class)

    private fun org.springframework.beans.factory.ListableBeanFactory.has(type: kotlin.reflect.KClass<*>) = getBeansOfType(type.java).isNotEmpty()

    @Test
    fun `without a ledger store there is no webhook bean, route, public endpoint or guard at all`() {
        var alone: Set<String> = emptySet()
        WebApplicationContextRunner().withBean(ScriptedGateway::class.java, java.util.function.Supplier { gateway })
            .withConfiguration(AutoConfigurations.of(PaymentAutoConfiguration::class.java)).run { alone = it.beanDefinitionNames.toSet() }

        webWithoutLedger.withPropertyValues("skeleton.payment.webhook.path-token=a-token-that-is-long-enough-0123456789").run { context ->
            assertEquals(alone, context.beanDefinitionNames.toSet(), "not one bean more than payment alone")
            webhookTypes.forEach { assertTrue(!context.has(it), "${it.simpleName} must not exist without a store") }
            assertTrue(context.getBeansOfType(PublicEndpointContributor::class.java).isEmpty())
            assertTrue(context.getBeansOfType(DeployGuard::class.java).isEmpty())
        }
    }

    @Test
    fun `with a ledger the inbox, the handler, the route, the public endpoint and the guard stand - and the runner knows the webhook job`() {
        web.run { context ->
            webhookTypes.forEach { assertTrue(context.has(it), "${it.simpleName} must exist with a store") }
            assertEquals(1, context.getBeansOfType(PublicEndpointContributor::class.java).size)
            assertEquals(listOf("payment", "payment-country"), context.getBeansOfType(DeployGuard::class.java).values.map { it.name }.sorted())
            assertTrue(PaymentTaskTypes.WEBHOOK in context.getBean(PaymentTaskRunner::class.java).types)
        }
    }

    @Test
    fun `with the webhook route off the inbox and the handler stay for the operator - route, public endpoint and guard go`() {
        web.withPropertyValues("skeleton.payment.webhook.enabled=false").run { context ->
            assertTrue(context.has(PaymentWebhookInbox::class))
            assertTrue(context.has(PaymentWebhookTaskHandler::class))
            assertTrue(!context.has(PaymentWebhookController::class))
            assertTrue(!context.has(PaymentWebhookDeployGuard::class))
            assertTrue(context.getBeansOfType(PublicEndpointContributor::class.java).isEmpty())
        }
    }
}
