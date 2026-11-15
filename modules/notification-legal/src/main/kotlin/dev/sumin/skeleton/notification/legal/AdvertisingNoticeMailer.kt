package dev.sumin.skeleton.notification.legal

import dev.sumin.skeleton.common.contact.ContactAddresses
import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationSubscriber
import dev.sumin.skeleton.notification.NotificationSubscription
import dev.sumin.skeleton.notification.NotificationSubscriptionRegistry
import dev.sumin.skeleton.notification.advertising.AdvertisingNotificationTypes
import dev.sumin.skeleton.notification.mail.MailMessage
import dev.sumin.skeleton.notification.mail.MailSender
import org.slf4j.LoggerFactory

/**
 * 법정 통지(동의 처리 결과 `advertising.consent.changed` · 2년 재확인 안내 `advertising.consent.reconfirm`)를 **전자우편으로도** 보낸다 -
 * 받은편지함은 전송매체가 아니므로 푸시가 없는 앱의 통지가 닿는 길이다. 받는 사람 주소는 [ContactAddresses](계정 모듈이 구현), 없으면 그 사람은 건너뛴다.
 * 정보성이라 광고 게이트 밖이다(`notificationType` 을 달지 않는다). 모든 예외를 잡고 로그만 남긴다 - 브로커는 던진 구독자를 닫는다. [mail] · [contacts] 는 보낼 때 읽는다.
 */
class AdvertisingNoticeMailer(
    registry: NotificationSubscriptionRegistry,
    private val mail: () -> MailSender?,
    private val contacts: () -> ContactAddresses?,
) : AutoCloseable, NoticeMedium {
    private val log = LoggerFactory.getLogger(javaClass)
    private val types = setOf(AdvertisingNotificationTypes.CONSENT_CHANGED, AdvertisingNotificationTypes.CONSENT_RECONFIRM)

    private val subscription: NotificationSubscription =
        registry.subscribe(topics = setOf(ADVERTISING_NOTICE_TOPIC), subscriber = NotificationSubscriber { event -> onNotification(event) })

    override fun ready(): Boolean = mail() != null && contacts() != null

    fun onNotification(event: NotificationEvent) {
        if (event.type !in types) return
        try {
            val sender = mail() ?: return
            val addresses = contacts() ?: return
            val title = event.title ?: return
            event.recipientIds.forEach { id ->
                val to = addresses.contactEmail(id) ?: return@forEach
                val result = sender.send(MailMessage(to = listOf(to), subject = title, text = event.message.orEmpty()))
                if (!result.accepted) log.warn("advertising notice mail not accepted account={} type={}", id, event.type)
            }
        } catch (e: Exception) {
            log.warn("advertising notice mail failed event={} exception={}", event.id, e.javaClass.simpleName)
        }
    }

    override fun close() {
        subscription.close()
    }
}
