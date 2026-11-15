package dev.sumin.skeleton.point

import dev.sumin.skeleton.common.authz.AuthzAutoConfiguration
import dev.sumin.skeleton.common.authz.PermissionDeclaration
import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.reward.RewardIssuer
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.wake.OnRequestTask
import dev.sumin.skeleton.common.wake.PeriodicRunCheck
import dev.sumin.skeleton.common.wake.SimpleOnRequestTask
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.env.Environment
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
    fun pointWallet(store: PointStore, time: TimeProvider, properties: PointProperties): PointWallet =
        StorePointWallet(store, time, holdTimeout = properties.reservations.takeIf { it.enabled }?.holdTimeout)

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

    /** 요청 구동 모드(인스턴스 0대 배포)의 깨우기가 부른다 - [PointWallet.expireDue] 가 멱등이다 */
    @Bean
    @ConditionalOnBean(PointWallet::class)
    @ConditionalOnMissingBean(name = ["pointExpiryTask"])
    fun pointExpiryTask(wallet: PointWallet, time: TimeProvider): OnRequestTask = SimpleOnRequestTask("point-expire") { wallet.expireDue(time.now()) }

    /**
     * 잡아 두기를 켰을 때만. 돌릴 길(scheduler · 요청 구동)이 없으면 버려진 건이 조용히 안 풀려 포인트가 묶이므로 기동 실패(`PeriodicRunCheck`).
     * `scheduler` 표시는 [PointReservationSweep.scheduled] 가 읽고, 요청 구동 모드는 아래 [OnRequestTask] 가 부른다 - 둘 다 풀기가 멱등이다.
     */
    @Bean
    @ConditionalOnBean(PointWallet::class)
    @ConditionalOnProperty("skeleton.point.reservations.enabled", havingValue = "true")
    @ConditionalOnMissingBean
    fun pointReservationSweep(wallet: PointWallet, time: TimeProvider, environment: Environment): PointReservationSweep {
        PeriodicRunCheck.require("skeleton.point.reservations", environment)
        return PointReservationSweep(wallet, time)
    }

    @Bean
    @ConditionalOnBean(PointReservationSweep::class)
    @ConditionalOnMissingBean(name = ["pointReservationSweepTask"])
    fun pointReservationSweepTask(sweep: PointReservationSweep): OnRequestTask = SimpleOnRequestTask("point-reservation-sweep") { sweep.run() }

    @Bean
    @ConditionalOnBean(PointStore::class)
    @ConditionalOnMissingBean(name = ["pointErasure"])
    fun pointErasure(store: PointStore): AccountErasureListener = PointErasure(store)
}
