package dev.sumin.skeleton.notification.push

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

/** `auth` 가 클래스패스에 있을 때만: 세션 사건으로 기기 줄을 지우고, 기기에 호출자의 세션 id 를 적는다. 없으면 이 연동만 빠진다(`src/noOptionalTest`) */
@AutoConfiguration(before = [PushAutoConfiguration::class])
@ConditionalOnClass(name = ["dev.sumin.skeleton.auth.session.SessionEventListener", "dev.sumin.skeleton.auth.principal.CurrentPrincipal"])
@ConditionalOnProperty(prefix = "skeleton.notification.push", name = ["enabled"], havingValue = "true")
class PushSessionAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = ["pushSessionEventListener"])
    fun pushSessionEventListener(devices: PushDeviceStore): dev.sumin.skeleton.auth.session.SessionEventListener = PushSessionEventListener(devices)

    @Bean
    @ConditionalOnMissingBean(PushSessionIdResolver::class)
    fun authPushSessionIdResolver(): PushSessionIdResolver = AuthPushSessionIdResolver()
}
