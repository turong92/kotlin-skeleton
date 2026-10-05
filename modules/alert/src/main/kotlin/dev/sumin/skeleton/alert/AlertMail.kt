package dev.sumin.skeleton.alert

import dev.sumin.skeleton.notification.mail.MailMessage
import dev.sumin.skeleton.notification.mail.MailSender

/** `notification-mail` 의 [MailSender] 로 보내는 두 번째 채널. [AlertMailAutoConfiguration] 이 그 모듈이 있고 받는 주소가 있을 때만 등록한다 */
class MailAlertChannel(private val sender: MailSender, private val recipients: List<String>) : AlertChannel {
    override val name = "mail"

    override fun send(message: AlertMessage) {
        val result = sender.send(MailMessage(recipients, AlertText.headline(message), AlertText.body(message)))
        if (!result.accepted) throw AlertDeliveryException("mail not accepted")
    }
}
