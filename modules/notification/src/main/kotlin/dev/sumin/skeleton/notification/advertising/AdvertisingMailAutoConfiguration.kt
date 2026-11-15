package dev.sumin.skeleton.notification.advertising

import dev.sumin.skeleton.notification.mail.MailSender
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment

/**
 * `notification-mail` 이 클래스패스에 있고 광고 종류(`skeleton.notification.advertising.types`)가 정해졌을 때, 앱에 있는 **모든** [MailSender] 빈(앱이 바꿔 낀 것도)을 [AdvertisingMailSender] 로 감싼다 -
 * 광고 종류로 표시된 메일이 게이트 없이 나가는 길을 두지 않으려는 것이다. 광고 종류가 없으면 아무것도 바꾸지 않는다. `notification-mail` 은 선택 연동이다(없으면 이 클래스는 읽히지도 않는다).
 */
@AutoConfiguration
@ConditionalOnClass(name = ["dev.sumin.skeleton.notification.mail.MailSender"])
class AdvertisingMailAutoConfiguration {
    companion object {
        @JvmStatic
        @Bean
        fun advertisingMailSenderPostProcessor(environment: Environment, gate: ObjectProvider<AdvertisingGate>): BeanPostProcessor {
            val configured = Binder.get(environment).bind("skeleton.notification.advertising.types", Bindable.listOf(String::class.java)).orElse(emptyList()).orEmpty().any { it.isNotBlank() }
            return object : BeanPostProcessor {
                override fun postProcessAfterInitialization(bean: Any, beanName: String): Any =
                    if (configured && bean is MailSender && bean !is AdvertisingMailSender) AdvertisingMailSender(bean) { gate.getObject() } else bean
            }
        }
    }
}
