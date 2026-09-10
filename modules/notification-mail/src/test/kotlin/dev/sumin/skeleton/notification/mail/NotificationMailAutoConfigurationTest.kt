package dev.sumin.skeleton.notification.mail

import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class NotificationMailAutoConfigurationTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration::class.java, NotificationMailAutoConfiguration::class.java))

    @Test
    fun `기본은 꺼져 있다 (호스트가 있어도 enabled 없으면 빈 없음)`() {
        runner.withPropertyValues("spring.mail.host=smtp.example.com").run { ctx ->
            assertTrue(ctx.getBeansOfType(MailSender::class.java).isEmpty())
        }
    }

    @Test
    fun `enabled + spring_mail_host + from 이면 SmtpMailSender 가 뜬다`() {
        runner.withPropertyValues(
            "spring.mail.host=smtp.example.com",
            "skeleton.notification-mail.enabled=true",
            "skeleton.notification-mail.from=no-reply@example.com",
        ).run { ctx ->
            assertTrue(ctx.getBean(MailSender::class.java) is SmtpMailSender)
        }
    }
}
