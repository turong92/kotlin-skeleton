package dev.sumin.skeleton.ads.webrewarded

import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.reward.RewardIssuer
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.web.ClientIps
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * 웹 보상형 광고 세션 — 서블릿 웹 앱이고 Spring Security(호출자)가 있고 `skeleton.ads-web-rewarded.enabled=true` 이고 저장소([WebRewardStore] 빈 — `ads-web-rewarded-jdbc`)가 있을 때만 선다.
 * `reward-type` 을 정했으면 그 종류의 [RewardIssuer] 빈이 있어야 하고(없거나 params 가 틀리면 기동 실패), 비우면 1회용 토큰 모드다.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["org.springframework.security.core.Authentication"])
@ConditionalOnProperty(prefix = "skeleton.ads-web-rewarded", name = ["enabled"], havingValue = "true")
@ConditionalOnBean(WebRewardStore::class)
@EnableConfigurationProperties(WebRewardedProperties::class)
class WebRewardedAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun webRewardService(store: WebRewardStore, properties: WebRewardedProperties, issuers: ObjectProvider<RewardIssuer>, time: TimeProvider): WebRewardService =
        WebRewardService(store, properties, properties.rewardType?.let { type -> issuers.orderedStream().filter { it.type == type }.findFirst().orElse(null) }, time)

    @Bean
    @ConditionalOnMissingBean
    fun webRewardedController(service: WebRewardService, clientIps: ObjectProvider<ClientIps>): WebRewardedController =
        WebRewardedController(service, clientIps.getIfAvailable { ClientIps() })

    @Bean
    @ConditionalOnMissingBean(name = ["webRewardErasure"])
    fun webRewardErasure(store: WebRewardStore): AccountErasureListener = WebRewardErasure(store)
}
