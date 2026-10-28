package dev.sumin.skeleton.reward

import dev.sumin.skeleton.common.authz.PermissionDeclaration
import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.reward.RewardIssuer
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.reward.erasure.RewardAccountErasureListener
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * 지급 건 기록([RewardGrants]) · 일꾼([RewardWorker]) · 운영 권한. 저장소와 작업 포트([RewardGrantStore] · [RewardTasks])는 `reward-jdbc` 가 내놓는다 —
 * 없으면 서비스 빈이 켜지지 않는다. 지급기([RewardIssuer])가 하나도 없어도 뜬다(지급 건은 `FAILED` 로 남는다).
 * 알림은 선택 통합이라 이 클래스는 `notification` 타입을 참조하지 않는다(구현은 [dev.sumin.skeleton.reward.notification.RewardNotificationAutoConfiguration]).
 */
@AutoConfiguration(afterName = ["dev.sumin.skeleton.reward.jdbc.RewardJdbcAutoConfiguration"])
@EnableConfigurationProperties(RewardProperties::class)
class RewardAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = ["rewardPermissionDeclaration"])
    fun rewardPermissionDeclaration(): PermissionDeclaration = RewardPermissions.declaration()

    @Bean
    @ConditionalOnMissingBean(RewardNotifier::class)
    fun noRewardNotifier(): RewardNotifier = RewardNotifier { _, _ -> }

    @Bean
    @ConditionalOnBean(RewardGrantStore::class, RewardTasks::class)
    @ConditionalOnMissingBean
    fun rewardGrantService(store: RewardGrantStore, tasks: RewardTasks, time: ObjectProvider<TimeProvider>): RewardGrantService =
        RewardGrantService(store, tasks, time.getIfAvailable { TimeProvider.systemUtc() })

    @Bean
    @ConditionalOnBean(RewardGrantService::class)
    @ConditionalOnMissingBean(RewardGrants::class)
    fun rewardGrants(service: RewardGrantService): RewardGrants = service

    @Bean
    @ConditionalOnBean(RewardGrantStore::class)
    @ConditionalOnMissingBean
    fun rewardWorker(
        store: RewardGrantStore,
        issuers: ObjectProvider<RewardIssuer>,
        notifier: ObjectProvider<RewardNotifier>,
        time: ObjectProvider<TimeProvider>,
    ): RewardWorker = RewardWorker(
        store,
        { issuers.orderedStream().toList() },
        { notifier.getObject() },
        time.getIfAvailable { TimeProvider.systemUtc() },
    )

    @Bean
    @ConditionalOnBean(RewardGrantStore::class)
    @ConditionalOnMissingBean(name = ["rewardAccountErasureListener"])
    fun rewardAccountErasureListener(store: RewardGrantStore): AccountErasureListener = RewardAccountErasureListener(store)
}
