package dev.sumin.skeleton.payment.web

import dev.sumin.skeleton.payment.PaymentAutoConfiguration
import dev.sumin.skeleton.payment.PaymentLedgerAutoConfiguration
import dev.sumin.skeleton.payment.PaymentCatalog
import dev.sumin.skeleton.payment.PaymentOperatorPolicy
import dev.sumin.skeleton.payment.PaymentTasks
import dev.sumin.skeleton.payment.testing.CheerCatalog
import dev.sumin.skeleton.payment.testing.InMemoryPaymentLedgerStore
import dev.sumin.skeleton.payment.testing.InMemoryPaymentTasks
import dev.sumin.skeleton.payment.testing.ScriptedGateway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** 켜짐 · 꺼짐 조합 — 장부가 없으면 빈이 하나도 늘지 않고, 운영자 경로는 기본 꺼짐, 카탈로그가 없으면 주문 만들기 경로가 없다. */
class PaymentHttpVariantsTest {
    @Configuration(proxyBeanMethods = false)
    class Ledger {
        @Bean fun store() = InMemoryPaymentLedgerStore()

        @Bean fun tasks(store: InMemoryPaymentLedgerStore): PaymentTasks = InMemoryPaymentTasks(store)
    }

    private val gateway = ScriptedGateway()

    private val all = WebApplicationContextRunner()
        .withBean(ScriptedGateway::class.java, java.util.function.Supplier { gateway })
        .withConfiguration(AutoConfigurations.of(PaymentAutoConfiguration::class.java, PaymentLedgerAutoConfiguration::class.java, PaymentHttpAutoConfiguration::class.java))

    private fun names(context: AssertableWebApplicationContext) = context.beanDefinitionNames.toSet()

    @Test
    fun `without a ledger store the HTTP configuration adds not one bean - even in a web application with the admin switch on`() {
        var alone: Set<String> = emptySet()
        WebApplicationContextRunner()
            .withBean(ScriptedGateway::class.java, java.util.function.Supplier { gateway })
            .withConfiguration(AutoConfigurations.of(PaymentAutoConfiguration::class.java))
            .run { alone = names(it) }

        all.withPropertyValues("skeleton.payment.admin.enabled=true").run { context ->
            assertEquals(alone, names(context))
            assertTrue(context.getBeansOfType(PaymentController::class.java).isEmpty())
            assertTrue(context.getBeansOfType(PaymentAdminController::class.java).isEmpty())
        }
    }

    @Test
    fun `with a ledger and a catalog the user controllers stand and the admin controller does not until it is switched on`() {
        all.withUserConfiguration(Ledger::class.java).withBean(PaymentCatalog::class.java, java.util.function.Supplier { CheerCatalog() }).run { context ->
            assertEquals(1, context.getBeansOfType(PaymentController::class.java).size)
            assertEquals(1, context.getBeansOfType(PaymentOrderController::class.java).size)
            assertTrue(context.getBeansOfType(PaymentAdminController::class.java).isEmpty(), "the operator path is off by default")
            assertTrue(context.getBeansOfType(PaymentOperatorPolicy::class.java).isEmpty())
        }
        all.withUserConfiguration(Ledger::class.java).withBean(PaymentCatalog::class.java, java.util.function.Supplier { CheerCatalog() })
            .withPropertyValues("skeleton.payment.admin.enabled=true").run { context ->
                assertEquals(1, context.getBeansOfType(PaymentAdminController::class.java).size)
                assertEquals(1, context.getBeansOfType(PaymentOperatorPolicy::class.java).size)
            }
    }

    @Test
    fun `without a catalog bean there is no way to create an order over HTTP`() {
        all.withUserConfiguration(Ledger::class.java).run { context ->
            assertEquals(1, context.getBeansOfType(PaymentController::class.java).size)
            assertTrue(context.getBeansOfType(PaymentOrderController::class.java).isEmpty())
        }
    }

    @Test
    fun `http enabled false closes the user paths but leaves the webhook path to its own switch`() {
        all.withUserConfiguration(Ledger::class.java).withBean(PaymentCatalog::class.java, java.util.function.Supplier { CheerCatalog() })
            .withPropertyValues("skeleton.payment.http.enabled=false").run { context ->
                assertTrue(context.getBeansOfType(PaymentController::class.java).isEmpty())
                assertTrue(context.getBeansOfType(PaymentOrderController::class.java).isEmpty())
            }
    }

    @Test
    fun `an app's own operator policy replaces the role check`() {
        val policy = PaymentOperatorPolicy { true }
        all.withUserConfiguration(Ledger::class.java).withBean(PaymentOperatorPolicy::class.java, java.util.function.Supplier { policy })
            .withPropertyValues("skeleton.payment.admin.enabled=true").run { context ->
                assertEquals(policy, context.getBean(PaymentOperatorPolicy::class.java))
            }
    }
}
