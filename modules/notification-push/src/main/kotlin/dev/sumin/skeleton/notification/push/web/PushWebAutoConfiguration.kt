package dev.sumin.skeleton.notification.push.web

import dev.sumin.skeleton.common.web.PublicEndpointContributor
import dev.sumin.skeleton.notification.NotificationMuteRepository
import dev.sumin.skeleton.notification.advertising.AdvertisingGate
import dev.sumin.skeleton.notification.NotificationPreferencesProperties
import dev.sumin.skeleton.notification.push.PushAutoConfiguration
import dev.sumin.skeleton.notification.push.PushDeviceService
import dev.sumin.skeleton.notification.push.PushDeviceStore
import dev.sumin.skeleton.notification.push.PushOpenReporter
import dev.sumin.skeleton.notification.push.PushProperties
import dev.sumin.skeleton.notification.push.PushSender
import dev.sumin.skeleton.notification.push.PushSessionIdResolver
import dev.sumin.skeleton.notification.push.PushTypeMuteStore
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean

/** `/api/v1/push` — 푸시가 켜져 있고(`enabled`), 서블릿 웹 앱이며, Spring Security(호출자)가 클래스패스에 있을 때만 (`http.enabled=false` 로 뺀다) */
@AutoConfiguration(after = [PushAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["org.springframework.security.core.Authentication"])
@ConditionalOnProperty(prefix = "skeleton.notification.push.http", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(PushDeviceService::class)
class PushWebAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun pushController(
        service: PushDeviceService,
        devices: PushDeviceStore,
        mutes: PushTypeMuteStore,
        allChannelMutes: NotificationMuteRepository,
        notificationPreferences: NotificationPreferencesProperties,
        properties: PushProperties,
        gate: AdvertisingGate,
        senders: ObjectProvider<PushSender>,
        sessionIds: PushSessionIdResolver,
    ): PushController =
        PushController(service, devices, mutes, allChannelMutes, notificationPreferences, properties, gate, { senders.orderedStream().toList() }, sessionIds)

    /** 클릭 보고 경로 - 보고가 켜져 있을 때만(`PushOpenReporter` 빈) */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(PushOpenReporter::class)
    fun pushOpenController(reporter: PushOpenReporter): PushOpenController = PushOpenController(reporter)

    /** 서비스 워커는 로그인 쿠키가 없다 - 이 경로 하나만 공개하고 토큰 서명이 인증한다 */
    @Bean
    @ConditionalOnMissingBean(name = ["pushOpenPublicEndpointContributor"])
    @ConditionalOnBean(PushOpenReporter::class)
    fun pushOpenPublicEndpointContributor(): PublicEndpointContributor =
        PublicEndpointContributor { registry -> registry.add("POST", "/api/v1/push/opened") }
}
