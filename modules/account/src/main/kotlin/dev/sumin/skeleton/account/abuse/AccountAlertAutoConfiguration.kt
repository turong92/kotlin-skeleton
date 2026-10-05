package dev.sumin.skeleton.account.abuse

import dev.sumin.skeleton.account.events.AccountEventListener
import dev.sumin.skeleton.alert.OwnerAlerts
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/** `alert` 가 있으면 로그인 시도 폭주 · 리프레시 토큰 재사용을 주인 경보로 올린다 */
@AutoConfiguration(afterName = ["dev.sumin.skeleton.alert.AlertAutoConfiguration"])
@ConditionalOnClass(name = ["dev.sumin.skeleton.alert.OwnerAlerts"])
@ConditionalOnBean(OwnerAlerts::class)
class AccountAlertAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = ["alertingAccountEventListener"])
    fun alertingAccountEventListener(alerts: OwnerAlerts): AccountEventListener = AlertingAccountEventListener(alerts)
}
