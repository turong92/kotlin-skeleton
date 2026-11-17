package dev.sumin.skeleton.ads.webrewarded

import dev.sumin.skeleton.common.erasure.AccountErasureListener
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/**
 * 계정 삭제 고리 — **저장소([WebRewardStore] 빈)만 있으면** 선다. 모듈을 끄거나(`enabled=false`) 웹이 아닌 앱이어도 이미 쌓인 세션 줄(계정 id · IP 키)이 계정 삭제 때 지워져야 해서
 * 경로 스위치([WebRewardedAutoConfiguration])와 따로 둔다(`point` 의 `pointErasure` 와 같은 방식).
 */
@AutoConfiguration(afterName = ["dev.sumin.skeleton.ads.webrewarded.jdbc.WebRewardJdbcAutoConfiguration"])
@ConditionalOnBean(WebRewardStore::class)
class WebRewardedErasureAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = ["webRewardErasure"])
    fun webRewardErasure(store: WebRewardStore): AccountErasureListener = WebRewardErasure(store)
}
