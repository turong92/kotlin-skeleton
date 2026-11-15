package dev.sumin.skeleton.alert.client

import dev.sumin.skeleton.alert.AlertAutoConfiguration
import dev.sumin.skeleton.alert.AlertProperties
import dev.sumin.skeleton.alert.OwnerAlerts
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.time.asClock
import dev.sumin.skeleton.common.web.ClientIps
import dev.sumin.skeleton.common.web.InMemoryFixedWindowRateLimitStore
import dev.sumin.skeleton.common.web.PublicEndpointContributor
import dev.sumin.skeleton.common.web.RateLimitStore
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * 클라이언트 오류 받는 경로 — `skeleton.alert.client-errors.enabled=true` 일 때만(기본 꺼짐), `skeleton.alert.enabled=false` 면 같이 꺼진다.
 * 공개 경로는 `POST /api/v1/client-errors` 하나뿐이다.
 */
@AutoConfiguration(after = [AlertAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "skeleton.alert", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "skeleton.alert.client-errors", name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(AlertProperties::class)
class ClientErrorAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun clientErrorSurge(alerts: OwnerAlerts, properties: AlertProperties, time: ObjectProvider<TimeProvider>) =
        ClientErrorSurge(alerts, properties.clientErrors.surge, time.getIfAvailable { TimeProvider.systemUtc() })

    @Bean
    @ConditionalOnMissingBean
    fun clientErrorIntake(
        properties: AlertProperties,
        limits: ObjectProvider<RateLimitStore>,
        time: ObjectProvider<TimeProvider>,
        surge: ClientErrorSurge,
    ): ClientErrorIntake {
        val clock = time.getIfAvailable { TimeProvider.systemUtc() }
        // 저장소는 RateLimitStore 빈이 있으면 그것(redis-rate-limit 이면 여러 대 공유), 없으면 메모리
        return ClientErrorIntake(properties.clientErrors, limits.getIfAvailable { InMemoryFixedWindowRateLimitStore(clock.asClock()) }, clock, surge)
    }

    @Bean
    @ConditionalOnMissingBean
    fun clientErrorController(intake: ClientErrorIntake, clientIps: ObjectProvider<ClientIps>): ClientErrorController =
        ClientErrorController(intake, clientIps.getIfAvailable { ClientIps() })

    @Bean
    @ConditionalOnMissingBean(name = ["clientErrorPublicEndpointContributor"])
    fun clientErrorPublicEndpointContributor(): PublicEndpointContributor =
        PublicEndpointContributor { registry -> registry.add("POST", "/api/v1/client-errors") }
}
