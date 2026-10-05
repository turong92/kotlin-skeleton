package dev.sumin.skeleton.account.mail

import dev.sumin.skeleton.account.AccountAutoConfiguration
import dev.sumin.skeleton.notification.mail.MailMessage
import dev.sumin.skeleton.notification.mail.MailSender
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/** `notification-mail` 의 [MailSender] 로 보내는 길 */
class MailSenderTransport(private val sender: MailSender) : AccountMailTransport {
    override fun send(to: String, subject: String, text: String, html: String?): Boolean =
        sender.send(MailMessage(to = listOf(to), subject = subject, text = text, html = html)).accepted
}

/**
 * `notification-mail` 이 있고 그 발송기 빈이 만들어졌을 때(`skeleton.notification-mail.enabled=true` + `spring.mail.host`) 계정 메일을 그리로 보낸다.
 * 없으면 [LogOnlyMailTransport] 가 남는다 — 보내지 않고, stage · prod 의 DeployGuard 가 문제로 본다.
 */
@AutoConfiguration(
    before = [AccountAutoConfiguration::class],
    afterName = ["dev.sumin.skeleton.notification.mail.NotificationMailAutoConfiguration"],
)
@ConditionalOnClass(name = ["dev.sumin.skeleton.notification.mail.MailSender"])
@ConditionalOnBean(MailSender::class)
class AccountMailAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(AccountMailTransport::class)
    fun mailSenderAccountMailTransport(sender: MailSender): AccountMailTransport = MailSenderTransport(sender)
}
