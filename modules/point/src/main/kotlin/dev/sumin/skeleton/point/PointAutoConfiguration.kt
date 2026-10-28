package dev.sumin.skeleton.point

import dev.sumin.skeleton.common.authz.AuthzAutoConfiguration
import dev.sumin.skeleton.common.authz.PermissionDeclaration
import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.reward.RewardIssuer
import dev.sumin.skeleton.common.time.TimeProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * 포인트 지갑 · 보상 지급기 · 소멸 작업 · 계정 삭제 고리. **저장소([PointStore] 빈 — `point-jdbc`)가 있을 때만** 선다; 없으면 권한 선언 말고는 빈이 늘지 않는다.
 * 권한 `point.manage` 선언은 모듈이 클래스패스에 있으면 늘 낸다 (스위치와 무관 — 카탈로그와 yml 매핑 검증이 본다). HTTP 는 [PointWebAutoConfiguration].
 */
@AutoConfiguration(after = [AuthzAutoConfiguration::class])
@EnableConfigurationProperties(PointProperties::class)
class PointAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = ["pointPermissionDeclaration"])
    fun pointPermissionDeclaration(): PermissionDeclaration = PointPermissions.declaration

    @Bean
    @ConditionalOnBean(PointStore::class)
    @ConditionalOnMissingBean
    fun pointWallet(store: PointStore, time: TimeProvider): PointWallet = StorePointWallet(store, time)

    @Bean
    @ConditionalOnBean(PointWallet::class)
    @ConditionalOnMissingBean(name = ["pointRewardIssuer"])
    fun pointRewardIssuer(wallet: PointWallet, time: TimeProvider): RewardIssuer = PointRewardIssuer(wallet, time)

    /** `scheduler` 모듈이 클래스패스에 있을 때만 - 없으면 이 표시를 읽는 쪽이 없다 */
    @Bean
    @ConditionalOnBean(PointWallet::class)
    @ConditionalOnClass(name = ["dev.sumin.skeleton.scheduler.SkeletonScheduled"])
    @ConditionalOnMissingBean
    fun pointExpiryJob(wallet: PointWallet, time: TimeProvider): PointExpiryJob = PointExpiryJob(wallet, time)

    @Bean
    @ConditionalOnBean(PointStore::class)
    @ConditionalOnMissingBean(name = ["pointErasure"])
    fun pointErasure(store: PointStore): AccountErasureListener = PointErasure(store)
}
