package dev.sumin.skeleton.notification.mail

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** 모듈만 얹고 설정이 없으면 뜬다 — SMTP 없이, 메일 전송기는 `enabled=true` + 호스트 + from 을 줘야 생긴다. */
class NotificationMailBootWithoutConfigurationTest {
    @Test
    fun `boots with no configuration and no SMTP server`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration::class.java, NotificationMailAutoConfiguration::class.java))
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(MailSender::class.java)
            }
    }
}
