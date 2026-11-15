package dev.sumin.skeleton.notification.advertising

import dev.sumin.skeleton.notification.NotificationAutoConfiguration
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** notification-mail 이 클래스패스에 정말 없을 때 - 메일 광고 연동이 기동을 깨지 않는다 (소비자 시점) */
class AdvertisingWithoutMailModuleTest {
    @Test
    fun `the mail module is really absent here`() {
        assertFailsWith<ClassNotFoundException> { Class.forName("dev.sumin.skeleton.notification.mail.MailSender") }
    }

    @Test
    fun `boots with the mail hookup registered and no mail module`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NotificationAutoConfiguration::class.java, AdvertisingMailAutoConfiguration::class.java))
            .run { context -> assertNull(context.startupFailure) }
    }
}
