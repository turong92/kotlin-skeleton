package dev.sumin.skeleton.app.sample.push

import dev.sumin.skeleton.account.AccountRepository
import dev.sumin.skeleton.notification.advertising.AdvertisingRecipientZones
import java.time.ZoneId
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 광고의 야간(21~08시) 판정이 쓰는 받는 사람의 시간대 - 계정 프로필의 `timeZone`(`PATCH /api/v1/account/me`)이다.
 * 프로필에 없거나 읽지 못하면 null 이고, 그러면 모듈이 `skeleton.notification.advertising.fallback-zone` 을 쓴다(시스템 시간대는 쓰지 않는다).
 * 광고 종류를 설정하지 않은 앱에서는 아무것도 올리지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "skeleton.notification.push", name = ["enabled"], havingValue = "true")
class SamplePushConfiguration {
    @Bean
    fun advertisingRecipientZones(accounts: AccountRepository): AdvertisingRecipientZones = AdvertisingRecipientZones { accountId ->
        accounts.findById(accountId)?.timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() }
    }
}
