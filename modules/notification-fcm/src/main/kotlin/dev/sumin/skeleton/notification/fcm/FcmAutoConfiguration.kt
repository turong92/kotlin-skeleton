package dev.sumin.skeleton.notification.fcm

import dev.sumin.skeleton.common.deploy.DeployGuard
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.notification.push.PushSender
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/** 꺼짐이 기본 - `skeleton.notification.fcm.enabled=true` 로 켠다. 서비스 계정을 쓸 수 없으면 센더를 세우지 않는다(가드가 보호 환경 기동을 막고, 그 밖에서는 경고) */
@AutoConfiguration
@EnableConfigurationProperties(FcmProperties::class)
@ConditionalOnProperty(prefix = "skeleton.notification.fcm", name = ["enabled"], havingValue = "true")
class FcmAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = ["fcmSender"])
    fun fcmSender(properties: FcmProperties, http: ObjectProvider<FcmHttp>, time: ObjectProvider<TimeProvider>): PushSender? {
        val credentials = FcmConfigCheck.credentialsOf(properties) ?: return null
        return FcmSender(properties, credentials, http.getIfAvailable { JdkFcmHttp() }, time.getIfAvailable { TimeProvider.systemUtc() })
    }

    @Bean
    @ConditionalOnMissingBean(name = ["fcmDeployGuard"])
    fun fcmDeployGuard(properties: FcmProperties): DeployGuard = FcmDeployGuard(properties)
}
