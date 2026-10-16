package dev.sumin.skeleton.notification.mail

import dev.sumin.skeleton.common.project.NoProjectContextException
import dev.sumin.skeleton.common.project.ProjectAutoConfiguration
import dev.sumin.skeleton.common.project.ProjectContext
import dev.sumin.skeleton.common.project.ProjectRegistry
import jakarta.mail.internet.MimeMessage
import java.util.function.Supplier
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.JavaMailSenderImpl
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

    private class RecordingJavaMailSender : JavaMailSenderImpl() {
        val sent = mutableListOf<MimeMessage>()
        override fun doSend(mimeMessages: Array<out MimeMessage>, originalMessages: Array<out Any>?) { sent += mimeMessages }
    }

    private val projectRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ProjectAutoConfiguration::class.java, NotificationMailAutoConfiguration::class.java))
        .withBean(JavaMailSender::class.java, Supplier { RecordingJavaMailSender() })
        .withPropertyValues(
            "skeleton.notification-mail.enabled=true",
            "skeleton.projects.a.hosts=a.test", "skeleton.projects.a.schema=pa",
            "skeleton.projects.b.hosts=b.test", "skeleton.projects.b.schema=pb",
        )

    @Test
    fun `project mode - each project mails from its own address`() {
        projectRunner.withPropertyValues(
            "skeleton.projects.a.config.notification-mail.from=Project A <no-reply@a.test>",
            "skeleton.projects.b.config.notification-mail.from=Project B <no-reply@b.test>",
        ).run { ctx ->
            val sender = ctx.getBean(MailSender::class.java)
            val smtp = ctx.getBean(JavaMailSender::class.java) as RecordingJavaMailSender
            val registry = ctx.getBean(ProjectRegistry::class.java)
            registry.projects.forEach { p -> ProjectContext.runIn(p) { assertTrue(sender.send(MailMessage(listOf("x@y.z"), "s", "t")).accepted) } }
            val froms = smtp.sent.map { it.also(MimeMessage::saveChanges).from.single().toString() }
            assertTrue(froms[0].contains("no-reply@a.test") && froms[1].contains("no-reply@b.test"), froms.toString())
        }
    }

    @Test
    fun `project mode - a project without its own from stops the start - the shared from is not used for another service`() {
        projectRunner.withPropertyValues(
            "skeleton.notification-mail.from=shared@example.com",
            "skeleton.projects.a.config.notification-mail.from=no-reply@a.test",
        ).run { ctx ->
            val messages = generateSequence<Throwable>(ctx.startupFailure) { it.cause }.mapNotNull { it.message }.toList()
            assertTrue(messages.any { "project 'b'" in it && "notification-mail.from" in it }, messages.toString())
        }
    }

    @Test
    fun `outside a request in project mode a mail without a project fails instead of using some address`() {
        projectRunner.withPropertyValues(
            "skeleton.projects.a.config.notification-mail.from=no-reply@a.test",
            "skeleton.projects.b.config.notification-mail.from=no-reply@b.test",
        ).run { ctx ->
            assertFailsWith<NoProjectContextException> { ctx.getBean(MailSender::class.java).send(MailMessage(listOf("x@y.z"), "s", "t")) }
        }
    }
}
