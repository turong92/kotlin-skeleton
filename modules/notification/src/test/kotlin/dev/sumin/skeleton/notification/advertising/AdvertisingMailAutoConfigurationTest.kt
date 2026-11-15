package dev.sumin.skeleton.notification.advertising

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.notification.mail.MailMessage
import dev.sumin.skeleton.notification.mail.MailSendResult
import dev.sumin.skeleton.notification.mail.MailSender
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** 광고 종류를 정하면 `MailSender` 빈이 게이트를 거치고(앱이 바꿔 낀 것도), 안 정하면 아무것도 바뀌지 않는다 */
class AdvertisingMailAutoConfigurationTest {
    private val properties = AdvertisingProperties(types = listOf("promo.tips"), fallbackZone = "Asia/Seoul", senderName = "Example Co.", bodySuffix = "수신 거부: 설정 > 알림")
    private val gate = AdvertisingGate(properties, AdvertisingConsentPolicy { _, _ -> AdvertisingConsent(send = false, night = false) }, null, TimeProvider.systemUtc())
    private val delivered = mutableListOf<MailMessage>()

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AdvertisingMailAutoConfiguration::class.java))
        .withBean(MailSender::class.java, { MailSender { delivered += it; MailSendResult(accepted = true) } })
        .withBean(AdvertisingGate::class.java, { gate })

    private val message = MailMessage(to = listOf("a@example.com"), subject = "s", text = "t", notificationType = "promo.tips", accountId = "acc_a")

    @Test
    fun `with advertising types the mail sender in the context refuses an advertising mail without consent`() {
        runner.withPropertyValues("skeleton.notification.advertising.types=promo.tips").run { context ->
            assertTrue(context.getBean(MailSender::class.java) is AdvertisingMailSender)
            assertFalse(context.getBean(MailSender::class.java).send(message).accepted)
            assertTrue(delivered.isEmpty())
        }
    }

    @Test
    fun `without advertising types the mail sender is left as it is`() {
        runner.run { context ->
            assertFalse(context.getBean(MailSender::class.java) is AdvertisingMailSender)
            assertEquals(true, context.getBean(MailSender::class.java).send(message).accepted)
        }
    }
}
