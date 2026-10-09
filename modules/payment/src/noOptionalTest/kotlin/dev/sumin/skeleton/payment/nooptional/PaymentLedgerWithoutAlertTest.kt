package dev.sumin.skeleton.payment.nooptional

import dev.sumin.skeleton.payment.CreateOrderCommand
import dev.sumin.skeleton.payment.IdempotencyKey
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentCatalog
import dev.sumin.skeleton.payment.PaymentOrderStatus
import dev.sumin.skeleton.payment.PaymentOrders
import dev.sumin.skeleton.payment.PaymentReviewNotifier
import dev.sumin.skeleton.payment.PaymentReviewReason
import dev.sumin.skeleton.payment.PaymentTasks
import dev.sumin.skeleton.payment.testing.CheerCatalog
import dev.sumin.skeleton.payment.testing.InMemoryPaymentLedgerStore
import dev.sumin.skeleton.payment.testing.InMemoryPaymentTasks
import dev.sumin.skeleton.payment.testing.ScriptedGateway
import dev.sumin.skeleton.payment.testing.ScriptedGateway.ConfirmScript
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.context.annotation.ImportCandidates
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** `alert` 모듈이 클래스패스에 **정말 없을 때**(이 묶음은 compileOnly 를 따라 얹지 않는다) 장부가 도는 것을 증명한다. */
@ExtendWith(OutputCaptureExtension::class)
class PaymentLedgerWithoutAlertTest {
    /** 앱이 하는 일: 장부 저장소 · 작업 포트 · 결제사 · 카탈로그를 내놓는다. 모듈의 자동설정은 imports 파일로 **이름으로** 들어온다(운영과 같은 길). */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    class App {
        @Bean fun gateway() = ScriptedGateway()

        @Bean fun store() = InMemoryPaymentLedgerStore()

        @Bean fun tasks(store: InMemoryPaymentLedgerStore): PaymentTasks = InMemoryPaymentTasks(store)

        @Bean fun catalog(): PaymentCatalog = CheerCatalog()
    }

    private val runner = ApplicationContextRunner().withUserConfiguration(App::class.java)

    @Test
    fun `the module registers its ledger and alert configurations through the imports file`() {
        val names = ImportCandidates.load(AutoConfiguration::class.java, javaClass.classLoader).toList()

        assertTrue("dev.sumin.skeleton.payment.PaymentLedgerAutoConfiguration" in names, names.toString())
        assertTrue("dev.sumin.skeleton.payment.alert.PaymentAlertAutoConfiguration" in names, names.toString())
    }

    @Test
    fun `the alert module really is absent from this classpath`() {
        assertFailsWith<ClassNotFoundException> { Class.forName("dev.sumin.skeleton.alert.OwnerAlerts") }
    }

    @Test
    fun `the ledger runs and a review reason stays in the row and in the log`(output: CapturedOutput) {
        runner.run { context ->
            val gateway = context.getBean(ScriptedGateway::class.java)
            val store = context.getBean(InMemoryPaymentLedgerStore::class.java)
            assertSame(PaymentReviewNotifier.NONE, context.getBean(PaymentReviewNotifier::class.java))
            val orders = context.getBean(PaymentOrders::class.java)
            val created = orders.create(CreateOrderCommand("acc_1", "cheer", "cheer-3000", IdempotencyKey("k"))).order
            gateway.authenticate("pay_1", created.id, created.amount)
            gateway.script(ConfirmScript.ApproveWithTotal(PaymentAmount(1, "KRW")))

            val result = orders.confirm("acc_1", created.id, "pay_1", created.amount)

            assertEquals(PaymentOrderStatus.CONFIRMING, result.status)
            assertEquals(PaymentReviewReason.AMOUNT_MISMATCH, store.findOrder(created.id)!!.reviewReason)
            assertTrue(output.all.contains("needs review") && output.all.contains(created.id), output.all)
            assertTrue(!output.all.contains("pay_1"), "the log line carries the order id and the reason only")
        }
    }
}
