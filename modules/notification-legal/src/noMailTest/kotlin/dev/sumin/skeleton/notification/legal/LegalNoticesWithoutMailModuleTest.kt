package dev.sumin.skeleton.notification.legal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** notification-mail 이 클래스패스에 정말 없을 때 - 통지 메일 연동이 기동을 깨지 않고 아무 빈도 내지 않는다 (소비자 시점) */
class LegalNoticesWithoutMailModuleTest {
    @Test
    fun `the mail module is really absent here`() {
        assertFailsWith<ClassNotFoundException> { Class.forName("dev.sumin.skeleton.notification.mail.MailSender") }
    }

    @Test
    fun `the mail hookup boots and adds nothing`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NotificationLegalMailAutoConfiguration::class.java))
            .withPropertyValues("skeleton.notification.advertising.types=promo.tips")
            .run { context ->
                assertNull(context.startupFailure)
                assertEquals(0, context.getBeansOfType(Any::class.java).keys.count { it == "advertisingNoticeMailer" })
            }
    }
}
