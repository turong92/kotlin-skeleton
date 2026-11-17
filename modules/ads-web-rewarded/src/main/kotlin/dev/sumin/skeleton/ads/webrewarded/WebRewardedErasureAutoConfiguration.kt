package dev.sumin.skeleton.ads.webrewarded

import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.wake.OnRequestTask
import dev.sumin.skeleton.common.wake.PeriodicRunCheck
import dev.sumin.skeleton.common.wake.SimpleOnRequestTask
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment

/**
 * 저장소([WebRewardStore] 빈)만 있으면 서는 것들 — 경로 스위치([WebRewardedAutoConfiguration])와 따로 둔다. 모듈을 끄거나(`enabled=false`) 웹이 아닌 앱이어도
 * 이미 쌓인 세션 줄(계정 id · IP 키)이 계정 삭제 때 지워져야 하고, 보관 기한도 줄이 쌓이는 한 계속 돌아야 해서다(`point` 의 `pointErasure` 와 같은 방식).
 */
@AutoConfiguration(afterName = ["dev.sumin.skeleton.ads.webrewarded.jdbc.WebRewardJdbcAutoConfiguration"])
@ConditionalOnBean(WebRewardStore::class)
@EnableConfigurationProperties(WebRewardedProperties::class)
class WebRewardedErasureAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = ["webRewardErasure"])
    fun webRewardErasure(store: WebRewardStore): AccountErasureListener = WebRewardErasure(store)

    /** 보관 기한을 정했을 때만 - 기본 꺼짐(값을 비운 `retention:` 도 꺼짐). 돌릴 길(scheduler · 요청 구동)이 없으면 조용히 안 지우는 대신 기동 실패 */
    @Bean
    @ConditionalOnExpression("'\${skeleton.ads-web-rewarded.retention:}'.trim() != ''")
    @ConditionalOnMissingBean
    fun webRewardRetention(store: WebRewardStore, properties: WebRewardedProperties, time: ObjectProvider<TimeProvider>, environment: Environment): WebRewardRetention {
        PeriodicRunCheck.require("skeleton.ads-web-rewarded.retention", environment)
        return WebRewardRetention(store, checkNotNull(properties.retention), time.getIfAvailable { TimeProvider.systemUtc() })
    }

    /** 요청 구동 모드(인스턴스 0대 배포)의 깨우기가 부른다 - 지우기가 멱등이다 */
    @Bean
    @ConditionalOnBean(WebRewardRetention::class)
    @ConditionalOnMissingBean(name = ["webRewardRetentionTask"])
    fun webRewardRetentionTask(retention: WebRewardRetention): OnRequestTask = SimpleOnRequestTask("ads-web-rewarded-retention") { retention.purge() }
}
