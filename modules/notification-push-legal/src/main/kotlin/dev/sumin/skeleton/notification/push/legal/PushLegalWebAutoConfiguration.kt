package dev.sumin.skeleton.notification.push.legal

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.notification.push.AdvertisingGate
import dev.sumin.skeleton.notification.push.PushProperties
import dev.sumin.skeleton.notification.push.legal.web.AdvertisingController
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean

/** `GET /api/v1/push/advertising` - 광고 어댑터가 서 있고, 서블릿 웹 앱이며, Spring Security(호출자)가 클래스패스에 있을 때만 (`push.http.enabled=false` 로 뺀다) */
@AutoConfiguration(after = [PushLegalAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["org.springframework.security.core.Authentication"])
@ConditionalOnProperty(prefix = "skeleton.notification.push.http", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(LegalAdvertisingConsents::class)
class PushLegalWebAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun advertisingController(consents: LegalAdvertisingConsents, gate: AdvertisingGate, properties: PushProperties, time: ObjectProvider<TimeProvider>): AdvertisingController =
        AdvertisingController(consents, gate, properties, time.getIfAvailable { TimeProvider.systemUtc() })
}
