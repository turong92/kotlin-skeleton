package dev.sumin.skeleton.alert

import dev.sumin.skeleton.common.time.TimeProvider
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.WebApplicationContextRunner

/** job-queue-jdbc · notification-mail 이 클래스패스에 정말 없을 때 — compileOnly 연동이 기동을 깨지 않는다 (소비자 시점) */
class AlertWithoutOptionalModulesTest {
    @Test
    fun `the optional modules are really absent here`() {
        assertFailsWith<ClassNotFoundException> { Class.forName("dev.sumin.skeleton.jobqueue.jdbc.JobDeadListener") }
        assertFailsWith<ClassNotFoundException> { Class.forName("dev.sumin.skeleton.notification.mail.MailSender") }
    }

    @Test
    fun `boots with a webhook address and registers the webhook channel`() {
        WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AlertAutoConfiguration::class.java, AlertMailAutoConfiguration::class.java, AlertJobQueueAutoConfiguration::class.java))
            .withBean(TimeProvider::class.java, { TimeProvider.systemUtc() })
            .withPropertyValues("skeleton.alert.webhook-url=https://h.example/x", "skeleton.alert.mail-to=me@example.com")
            .run { context ->
                assertNotNull(context.getBean(OwnerAlerts::class.java))
                assertNotNull(context.getBean(AlertChannel::class.java))
            }
    }
}
