package dev.sumin.skeleton.badge

import dev.sumin.skeleton.badge.erasure.BadgeAccountErasureListener
import dev.sumin.skeleton.common.account.AccountFacts
import dev.sumin.skeleton.common.authz.PermissionDeclaration
import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.reward.RewardIssuer
import dev.sumin.skeleton.common.time.TimeProvider
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * 배지 정의 · 수여 · 회수 · 내 배지, 앱 API([Badges]), 보상 지급기(`badge`), 운영 권한, 탈퇴 고리. 저장소([BadgeStore])는 `badge-jdbc` 가 내놓는다 -
 * 없으면 서비스 빈이 켜지지 않는다. `reward` · `campaign` · `coupon` · `point` 를 모른다 - 지급기는 `platform` 의 계약을 구현해 내놓을 뿐이다.
 * 알림은 선택 통합이라 이 클래스는 `notification` 타입을 참조하지 않는다(구현은 [dev.sumin.skeleton.badge.notification.BadgeNotificationAutoConfiguration]).
 */
@AutoConfiguration(afterName = ["dev.sumin.skeleton.badge.jdbc.BadgeJdbcAutoConfiguration"])
@EnableConfigurationProperties(BadgeProperties::class)
class BadgeAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = ["badgePermissionDeclaration"])
    fun badgePermissionDeclaration(): PermissionDeclaration = BadgePermissions.declaration()

    @Bean
    @ConditionalOnMissingBean(BadgeNotifier::class)
    fun noBadgeNotifier(): BadgeNotifier = BadgeNotifier { _, _, _, _ -> }

    @Bean
    @ConditionalOnBean(BadgeStore::class)
    @ConditionalOnMissingBean
    fun badgeDefinitions(store: BadgeStore, time: ObjectProvider<TimeProvider>): BadgeDefinitions =
        BadgeDefinitions(store, time.getIfAvailable { TimeProvider.systemUtc() })

    @Bean
    @ConditionalOnBean(BadgeStore::class)
    @ConditionalOnMissingBean
    fun badgeAwards(
        store: BadgeStore,
        time: ObjectProvider<TimeProvider>,
        notifier: ObjectProvider<BadgeNotifier>,
        facts: ObjectProvider<AccountFacts>,
    ): BadgeAwards =
        BadgeAwards(store, time.getIfAvailable { TimeProvider.systemUtc() }, notifier = { notifier.getObject() }, accountFacts = { facts.getIfAvailable() })

    @Bean
    @ConditionalOnBean(BadgeAwards::class)
    @ConditionalOnMissingBean(Badges::class)
    fun badges(awards: BadgeAwards): Badges = awards

    @Bean
    @ConditionalOnBean(BadgeStore::class)
    @ConditionalOnMissingBean
    fun badgeCatalog(store: BadgeStore): BadgeCatalog = BadgeCatalog(store)

    @Bean
    @ConditionalOnBean(BadgeDefinitions::class, BadgeAwards::class)
    @ConditionalOnMissingBean(name = ["badgeRewardIssuer"])
    fun badgeRewardIssuer(definitions: BadgeDefinitions, awards: BadgeAwards): RewardIssuer = BadgeRewardIssuer(definitions, awards)

    @Bean
    @ConditionalOnBean(BadgeStore::class)
    @ConditionalOnMissingBean(name = ["badgeAccountErasureListener"])
    fun badgeAccountErasureListener(store: BadgeStore): AccountErasureListener = BadgeAccountErasureListener(store)
}
