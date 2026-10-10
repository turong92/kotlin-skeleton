package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.CheerCatalog
import dev.sumin.skeleton.payment.testing.InMemoryPaymentLedgerStore
import dev.sumin.skeleton.payment.testing.InMemoryPaymentTasks
import dev.sumin.skeleton.payment.testing.ScriptedGateway
import kotlin.test.Test
import kotlin.test.assertEquals
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

}

/** `payment-toss` 는 장부 유무를 이 **타입 이름**으로 찾는다(컴파일 의존 없이) — 포트의 이름 · 패키지가 바뀌면 그쪽 가드가 조용히 꺼진다. */
class PaymentLedgerStoreNameTest {
    @Test
    fun `the store port keeps the type name payment-toss looks for`() {
        assertEquals("dev.sumin.skeleton.payment.PaymentLedgerStore", PaymentLedgerStore::class.java.name)
    }
}
