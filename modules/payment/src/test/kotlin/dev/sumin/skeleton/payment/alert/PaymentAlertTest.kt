package dev.sumin.skeleton.payment.alert

import dev.sumin.skeleton.alert.AlertKind
import dev.sumin.skeleton.alert.AlertSeverity
import dev.sumin.skeleton.alert.OwnerAlerts
import dev.sumin.skeleton.payment.PaymentAutoConfiguration
import dev.sumin.skeleton.payment.PaymentLedgerAutoConfiguration
import dev.sumin.skeleton.payment.PaymentProviderCredentialAlert
import dev.sumin.skeleton.payment.PaymentReviewNotifier
import dev.sumin.skeleton.payment.PaymentReviewReason
import dev.sumin.skeleton.payment.PaymentTasks
import dev.sumin.skeleton.payment.testing.InMemoryPaymentLedgerStore
import dev.sumin.skeleton.payment.testing.InMemoryPaymentTasks
import dev.sumin.skeleton.payment.testing.ScriptedGateway
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Supplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class PaymentAlertTest {
    private class RecordingAlerts : OwnerAlerts {
        data class Emitted(val kind: AlertKind, val key: String, val detail: String, val immediate: Boolean)

        val emitted = CopyOnWriteArrayList<Emitted>()

        override fun emit(kind: AlertKind, key: String, detail: String, immediate: Boolean) {
            emitted += Emitted(kind, key, detail, immediate)
        }
    }

    private val store = InMemoryPaymentLedgerStore()

    private fun runner(withAlerts: RecordingAlerts?, withStore: Boolean = true): ApplicationContextRunner {
        var runner = ApplicationContextRunner().withBean(ScriptedGateway::class.java, Supplier { ScriptedGateway() })
        if (withStore) {
            runner = runner
                .withBean(InMemoryPaymentLedgerStore::class.java, Supplier { store })
                .withBean(PaymentTasks::class.java, Supplier { InMemoryPaymentTasks(store) })
        }
        if (withAlerts != null) runner = runner.withBean(OwnerAlerts::class.java, Supplier { withAlerts })
        return runner.withConfiguration(
            AutoConfigurations.of(PaymentAutoConfiguration::class.java, PaymentLedgerAutoConfiguration::class.java, PaymentAlertAutoConfiguration::class.java),
        )
    }

    @Test
    fun `a review reason emits PAYMENT_REVIEW_NEEDED keyed by the order id and carrying nothing else`() {
        val alerts = RecordingAlerts()
        runner(alerts).run { context ->
            context.getBean(PaymentReviewNotifier::class.java).flagged("ord_" + "a".repeat(32), PaymentReviewReason.AMOUNT_MISMATCH)
        }

        val emitted = alerts.emitted.single()
        assertEquals("PAYMENT_REVIEW_NEEDED", emitted.kind.name)
        assertEquals("ord_" + "a".repeat(32), emitted.key)
        assertEquals("", emitted.detail, "no amount, no account, no reason text")
        assertEquals(false, emitted.immediate, "after the commit when inside a transaction")
        assertTrue(emitted.kind.severity >= AlertSeverity.ERROR)
    }

    @Test
    fun `a rejected credential emits PAYMENT_PROVIDER_CREDENTIALS_REJECTED keyed by the provider id with a minimum interval and nothing else`() {
        val alerts = RecordingAlerts()
        runner(alerts).run { context ->
            context.getBean(PaymentProviderCredentialAlert::class.java).rejected("toss")
        }

        val emitted = alerts.emitted.single()
        assertEquals("PAYMENT_PROVIDER_CREDENTIALS_REJECTED", emitted.kind.name)
        assertEquals("toss", emitted.key)
        assertEquals("", emitted.detail)
        assertEquals(true, emitted.immediate, "it does not depend on a transaction commit")
        assertTrue(emitted.kind.severity >= AlertSeverity.ERROR)
        assertTrue(emitted.kind.minInterval >= java.time.Duration.ofMinutes(30), "a revoked key must not page on every payment")
    }

    @Test
    fun `without an OwnerAlerts bean there is no credential alert bean - the provider falls back to NONE`() {
        runner(null).run { context ->
            assertTrue(context.getBeansOfType(PaymentProviderCredentialAlert::class.java).isEmpty())
        }
    }

    @Test
    fun `without an OwnerAlerts bean the notifier does nothing`() {
        runner(null).run { context ->
            context.getBean(PaymentReviewNotifier::class.java).flagged("ord_x", PaymentReviewReason.MODE_MISMATCH)
            assertSame(PaymentReviewNotifier.NONE, context.getBean(PaymentReviewNotifier::class.java))
        }
    }

    @Test
    fun `without a ledger store the alert configuration registers nothing`() {
        val alerts = RecordingAlerts()
        var plain: Set<String> = emptySet()
        ApplicationContextRunner()
            .withBean(ScriptedGateway::class.java, Supplier { ScriptedGateway() })
            .withBean(OwnerAlerts::class.java, Supplier { alerts })
            .withConfiguration(AutoConfigurations.of(PaymentAutoConfiguration::class.java))
            .run { plain = it.beanDefinitionNames.toSet() }

        runner(alerts, withStore = false).run { context ->
            assertEquals(plain, context.beanDefinitionNames.toSet())
        }
    }
}
