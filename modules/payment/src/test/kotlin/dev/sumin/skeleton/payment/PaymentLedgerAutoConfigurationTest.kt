package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.payment.testing.CheerCatalog
import dev.sumin.skeleton.payment.testing.InMemoryPaymentLedgerStore
import dev.sumin.skeleton.payment.testing.InMemoryPaymentTasks
import dev.sumin.skeleton.payment.testing.ScriptedGateway
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

class PaymentLedgerAutoConfigurationTest {
    private val gateway = ScriptedGateway()

    private val base = ApplicationContextRunner()
        .withBean(ScriptedGateway::class.java, java.util.function.Supplier { gateway })
        .withConfiguration(AutoConfigurations.of(PaymentAutoConfiguration::class.java))

    private val withLedgerConfig = ApplicationContextRunner()
        .withBean(ScriptedGateway::class.java, java.util.function.Supplier { gateway })
        .withConfiguration(AutoConfigurations.of(PaymentAutoConfiguration::class.java, PaymentLedgerAutoConfiguration::class.java))

    @Configuration(proxyBeanMethods = false)
    class Ledger {
        @Bean fun store() = InMemoryPaymentLedgerStore()

        @Bean fun tasks(store: InMemoryPaymentLedgerStore): PaymentTasks = InMemoryPaymentTasks(store)
    }

    @Test
    fun `without a ledger store the bean set is exactly what payment alone gives - not one bean more`() {
        var alone: Set<String> = emptySet()
        base.run { alone = it.beanDefinitionNames.toSet() }

        withLedgerConfig.run { context ->
            assertEquals(alone, context.beanDefinitionNames.toSet())
            listOf(
                PaymentOrders::class, PaymentConvergence::class, PaymentFulfillment::class, PaymentLedgerWriter::class,
                PaymentTaskHandler::class, PaymentTaskRunner::class, PaymentOrderListener::class, PaymentReviewNotifier::class,
            ).forEach { type -> assertTrue(context.getBeansOfType(type.java).isEmpty(), "${type.simpleName} must not exist without a store") }
        }
    }

    @Test
    fun `with a store and a task port the ledger beans appear and the task runner knows the order, refund and webhook tasks`() {
        withLedgerConfig.withUserConfiguration(Ledger::class.java).withBean(PaymentCatalog::class.java, java.util.function.Supplier { CheerCatalog() }).run { context ->
            assertEquals(1, context.getBeansOfType(PaymentOrders::class.java).size)
            assertEquals(
                setOf(
                    PaymentTaskTypes.ORDER_EXPIRE, PaymentTaskTypes.ORDER_RECONCILE, PaymentTaskTypes.ORDER_FULFIL,
                    PaymentTaskTypes.REFUND_RECONCILE, PaymentTaskTypes.REFUND_APPLY, PaymentTaskTypes.WEBHOOK,
                ),
                context.getBean(PaymentTaskRunner::class.java).types,
            )
            val created = context.getBean(PaymentOrders::class.java).create(CreateOrderCommand("acc_1", "cheer", "cheer-3000", IdempotencyKey("k")))
            assertEquals(PaymentOrderStatus.PENDING, created.order.status)
            assertEquals(1, context.getBean(InMemoryPaymentLedgerStore::class.java).allOrders().size)
        }
    }

    @Test
    fun `without a catalog the service exists but refuses to create an order`() {
        withLedgerConfig.withUserConfiguration(Ledger::class.java).run { context ->
            assertFailsWith<PaymentUnavailableException> {
                context.getBean(PaymentOrders::class.java).create(CreateOrderCommand("acc_1", "cheer", "cheer-3000", IdempotencyKey("k")))
            }
        }
    }

    @Test
    fun `an app's own listener, notifier and time source win over the defaults`() {
        val listener = object : PaymentOrderListener {}
        val notifier = PaymentReviewNotifier { _, _ -> }
        val fixed = Instant.parse("2026-01-02T03:04:05Z")
        withLedgerConfig.withUserConfiguration(Ledger::class.java)
            .withBean(PaymentOrderListener::class.java, java.util.function.Supplier { listener })
            .withBean(PaymentReviewNotifier::class.java, java.util.function.Supplier { notifier })
            .withBean(TimeProvider::class.java, java.util.function.Supplier { TimeProvider.fixed(fixed) })
            .withBean(PaymentCatalog::class.java, java.util.function.Supplier { CheerCatalog() })
            .run { context ->
                assertSame(listener, context.getBean(PaymentOrderListener::class.java))
                assertSame(notifier, context.getBean(PaymentReviewNotifier::class.java))
                val created = context.getBean(PaymentOrders::class.java).create(CreateOrderCommand("acc_1", "cheer", "cheer-3000", IdempotencyKey("k")))
                assertEquals(fixed, created.order.createdAt)
            }
    }

    @Test
    fun `an app handler for another task type joins the runner`() {
        val extra = object : PaymentTaskHandler {
            override val type = "payment-app-extra"
            override fun handle(run: PaymentTaskRun) = Unit
        }
        withLedgerConfig.withUserConfiguration(Ledger::class.java).withBean(PaymentTaskHandler::class.java, java.util.function.Supplier { extra }).run { context ->
            assertTrue("payment-app-extra" in context.getBean(PaymentTaskRunner::class.java).types)
        }
    }

    @Test
    fun `the checkout deadline and the reconcile keys come from the one skeleton payment prefix`() {
        withLedgerConfig.withUserConfiguration(Ledger::class.java)
            .withPropertyValues("skeleton.payment.checkout-ttl=10m", "skeleton.payment.reconcile.max-attempts=7")
            .withBean(PaymentCatalog::class.java, java.util.function.Supplier { CheerCatalog() })
            .run { context ->
                val created = context.getBean(PaymentOrders::class.java).create(CreateOrderCommand("acc_1", "cheer", "cheer-3000", IdempotencyKey("k"))).order
                assertEquals(java.time.Duration.ofMinutes(10), java.time.Duration.between(created.createdAt, created.checkoutExpiresAt))
                assertEquals(7, context.getBean(InMemoryPaymentTasks::class.java).all(PaymentTaskTypes.ORDER_EXPIRE).single().maxAttempts)
            }
    }
}

/** `payment-toss` 는 장부 유무를 이 **타입 이름**으로 찾는다(컴파일 의존 없이) — 포트의 이름 · 패키지가 바뀌면 그쪽 가드가 조용히 꺼진다. */
class PaymentLedgerStoreNameTest {
    @Test
    fun `the store port keeps the type name payment-toss looks for`() {
        assertEquals("dev.sumin.skeleton.payment.PaymentLedgerStore", PaymentLedgerStore::class.java.name)
    }
}
