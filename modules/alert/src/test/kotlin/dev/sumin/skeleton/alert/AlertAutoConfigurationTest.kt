package dev.sumin.skeleton.alert

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.jobqueue.jdbc.JobDeadListener
import dev.sumin.skeleton.notification.mail.MailSender
import dev.sumin.skeleton.notification.mail.MailSendResult
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.boot.web.servlet.FilterRegistrationBean

class AlertAutoConfigurationTest {
    private val runner = WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AlertAutoConfiguration::class.java, AlertMailAutoConfiguration::class.java, AlertJobQueueAutoConfiguration::class.java))
        .withBean(TimeProvider::class.java, { TimeProvider.systemUtc() })

    @Test
    fun `without a webhook address the app still gets OwnerAlerts, but no filter, no dead-job hook, no channel`() {
        runner.run { context ->
            assertNotNull(context.getBean(OwnerAlerts::class.java))
            assertTrue(context.getBeansOfType(AlertChannel::class.java).isEmpty())
            assertTrue(context.getBeansOfType(JobDeadListener::class.java).isEmpty())
            assertTrue(context.getBeansOfType(FilterRegistrationBean::class.java).values.none { it.filter is ServerErrorSurgeFilter })
        }
    }

    @Test
    fun `the whole module can be switched off`() {
        runner.withPropertyValues("skeleton.alert.enabled=false", "skeleton.alert.webhook-url=https://h.example/x").run { context ->
            assertTrue(context.getBeansOfType(OwnerAlerts::class.java).isEmpty())
        }
    }

    @Test
    fun `a webhook address turns on the channel, the 5xx filter and the dead-job hook`() {
        runner.withPropertyValues("skeleton.alert.webhook-url=https://h.example/x").run { context ->
            assertEquals(listOf("webhook"), context.getBeansOfType(AlertChannel::class.java).values.map { it.name })
            assertTrue(context.getBeansOfType(FilterRegistrationBean::class.java).values.any { it.filter is ServerErrorSurgeFilter })
            assertEquals(1, context.getBeansOfType(JobDeadListener::class.java).size)
        }
    }

    @Test
    fun `a mail channel appears only with a MailSender bean and recipients`() {
        val sender = MailSender { MailSendResult(true) }
        runner.withPropertyValues("skeleton.alert.webhook-url=https://h.example/x", "skeleton.alert.mail-to=me@example.com")
            .run { context -> assertEquals(listOf("webhook"), context.getBeansOfType(AlertChannel::class.java).values.map { it.name }) }
        runner.withBean(MailSender::class.java, { sender }).withPropertyValues("skeleton.alert.webhook-url=https://h.example/x", "skeleton.alert.mail-to=me@example.com")
            .run { context -> assertEquals(setOf("webhook", "mail"), context.getBeansOfType(AlertChannel::class.java).values.map { it.name }.toSet()) }
    }

    @Test
    fun `an app can replace the store, the channels or OwnerAlerts itself`() {
        val store = object : AlertStore {
            override fun record(kind: AlertKind, key: String, severity: AlertSeverity, title: String, detail: String, now: Instant, minInterval: Duration) = AlertRecorded(true, 0, 1)
        }
        runner.withBean(AlertStore::class.java, { store }).run { context -> assertTrue(context.getBean(AlertStore::class.java) === store) }
        val custom = OwnerAlerts.NONE
        runner.withBean(OwnerAlerts::class.java, { custom }).run { context -> assertTrue(context.getBean(OwnerAlerts::class.java) === custom) }
    }

    @Test
    fun `without the job-queue module on the classpath the alert module still boots`() {
        runner.withClassLoader(FilteredClassLoader(JobDeadListener::class.java)).withPropertyValues("skeleton.alert.webhook-url=https://h.example/x")
            .run { context -> assertNotNull(context.getBean(OwnerAlerts::class.java)) }
    }
}
