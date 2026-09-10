package dev.sumin.skeleton.notification.mail

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.mail.javamail.JavaMailSender

/** `skeleton.notification-mail.enabled=true` 이고 `spring.mail.host` 가 있어 [JavaMailSender] 가 만들어졌을 때만 켜진다. */
@AutoConfiguration(after = [MailSenderAutoConfiguration::class])
@ConditionalOnProperty(prefix = "skeleton.notification-mail", name = ["enabled"], havingValue = "true")
@ConditionalOnBean(JavaMailSender::class)
@EnableConfigurationProperties(MailProperties::class)
class NotificationMailAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(MailSender::class)
    fun smtpMailSender(javaMailSender: JavaMailSender, properties: MailProperties): MailSender {
        require(properties.from.isNotBlank()) { "skeleton.notification-mail.from must be set when mail is enabled." }
        return SmtpMailSender(javaMailSender, properties.from)
    }
}
