package dev.sumin.skeleton.notification.legal

import dev.sumin.skeleton.common.contact.ContactAddresses
import dev.sumin.skeleton.notification.NotificationSubscriptionRegistry
import dev.sumin.skeleton.notification.mail.MailSender
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional

/**
 * 법정 통지를 메일로도 보낸다 - `notification-mail` 이 클래스패스에 있고 광고 종류가 정해졌을 때 선다(선택 연동, 없으면 읽히지도 않는다).
 * `MailSender` 와 `ContactAddresses`(계정 모듈이 낸다)는 보낼 때 찾고, 없으면 그 통지는 메일로 안 간다.
 */
@AutoConfiguration(after = [NotificationLegalAutoConfiguration::class])
@ConditionalOnClass(name = ["dev.sumin.skeleton.notification.mail.MailSender"])
@Conditional(AdvertisingTypesConfigured::class)
@ConditionalOnBean(AdvertisingConsentNotifier::class)
class NotificationLegalMailAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun advertisingNoticeMailer(registry: NotificationSubscriptionRegistry, mail: ObjectProvider<MailSender>, contacts: ObjectProvider<ContactAddresses>): AdvertisingNoticeMailer =
        AdvertisingNoticeMailer(registry, { mail.getIfAvailable() }, { contacts.getIfAvailable() })
}
