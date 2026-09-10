package dev.sumin.skeleton.time

import dev.sumin.skeleton.common.time.TimeAutoConfiguration
import dev.sumin.skeleton.common.time.TimeProvider
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * `modules:time` 진입점. 필터 없음 — [TimeContext] 가 호출 시점에 요청 헤더/설정을 읽는다
 * (그래서 Security 뒤에서도 계정 설정을 볼 수 있다). 현재 시각은 platform 의 [TimeProvider] 를 쓴다.
 */
@AutoConfiguration(after = [TimeAutoConfiguration::class])
@EnableConfigurationProperties(TimeContextProperties::class)
class TimeContextAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun timeContext(
        props: TimeContextProperties,
        preferences: ObjectProvider<UserTimePreferences>,
        timeProvider: ObjectProvider<TimeProvider>,
    ) = TimeContext(props, preferences, timeProvider.getIfAvailable { TimeProvider.systemUtc() })

    @Bean
    @ConditionalOnMissingBean
    fun timeFormatter(ctx: TimeContext) = TimeFormatter(ctx)
}
