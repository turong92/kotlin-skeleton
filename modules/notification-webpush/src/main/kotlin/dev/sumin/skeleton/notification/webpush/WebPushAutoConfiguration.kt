package dev.sumin.skeleton.notification.webpush

import dev.sumin.skeleton.common.deploy.DeployGuard
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.notification.push.PushSender
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/** 꺼짐이 기본 - `skeleton.notification.webpush.enabled=true` 로 켠다. VAPID 설정을 쓸 수 없으면 센더를 세우지 않는다(가드가 보호 환경 기동을 막고, 그 밖에서는 경고) */
@AutoConfiguration
@EnableConfigurationProperties(WebPushProperties::class)
@ConditionalOnProperty(prefix = "skeleton.notification.webpush", name = ["enabled"], havingValue = "true")
class WebPushAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = ["webPushSender"])
    fun webPushSender(properties: WebPushProperties, http: ObjectProvider<WebPushHttp>, time: ObjectProvider<TimeProvider>): PushSender? {
        val keys = WebPushConfigCheck.keysOf(properties) ?: return null
        return WebPushSender(
            properties = properties,
            vapid = keys,
            policy = WebPushEndpointPolicy(properties.allowedHosts),
            http = http.getIfAvailable { JdkWebPushHttp() },
            time = time.getIfAvailable { TimeProvider.systemUtc() },
        )
    }

    @Bean
    @ConditionalOnMissingBean(name = ["webPushDeployGuard"])
    fun webPushDeployGuard(properties: WebPushProperties): DeployGuard = WebPushDeployGuard(properties)
}
