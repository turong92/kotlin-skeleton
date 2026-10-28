package dev.sumin.skeleton.coupon

import dev.sumin.skeleton.common.authz.PermissionDeclaration
import dev.sumin.skeleton.common.discount.DiscountSource
import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.reward.RewardIssuer
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.coupon.erasure.CouponAccountErasureListener
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * 쿠폰 정의 · 발급 · 공용 코드 · 쿠폰함, 보상 지급기(`coupon`)와 결제 할인 수단(`coupon`), 운영 권한. 저장소([CouponStore])는 `coupon-jdbc` 가 내놓는다 -
 * 없으면 서비스 빈이 켜지지 않는다. 지급기 · 할인 수단은 이 모듈이 `platform` 의 계약을 구현해 내놓을 뿐이다 - 이벤트(`campaign`) · 결제(`payment`)는 이 모듈을 모른다.
 */
@AutoConfiguration(afterName = ["dev.sumin.skeleton.coupon.jdbc.CouponJdbcAutoConfiguration"])
@EnableConfigurationProperties(CouponProperties::class)
class CouponAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = ["couponPermissionDeclaration"])
    fun couponPermissionDeclaration(): PermissionDeclaration = CouponPermissions.declaration()

    @Bean
    @ConditionalOnBean(CouponStore::class)
    @ConditionalOnMissingBean
    fun couponDefinitions(store: CouponStore, time: ObjectProvider<TimeProvider>): CouponDefinitions =
        CouponDefinitions(store, time.getIfAvailable { TimeProvider.systemUtc() })

    @Bean
    @ConditionalOnBean(CouponStore::class)
    @ConditionalOnMissingBean
    fun couponIssuance(store: CouponStore, time: ObjectProvider<TimeProvider>): CouponIssuance =
        CouponIssuance(store, time.getIfAvailable { TimeProvider.systemUtc() })

    @Bean
    @ConditionalOnBean(CouponStore::class)
    @ConditionalOnMissingBean
    fun couponCodes(store: CouponStore, issuance: CouponIssuance, time: ObjectProvider<TimeProvider>): CouponCodes =
        CouponCodes(store, issuance, time.getIfAvailable { TimeProvider.systemUtc() })

    @Bean
    @ConditionalOnBean(CouponStore::class)
    @ConditionalOnMissingBean
    fun couponWallet(store: CouponStore, time: ObjectProvider<TimeProvider>): CouponWallet =
        CouponWallet(store, time.getIfAvailable { TimeProvider.systemUtc() })

    @Bean
    @ConditionalOnBean(CouponDefinitions::class, CouponIssuance::class)
    @ConditionalOnMissingBean(name = ["couponRewardIssuer"])
    fun couponRewardIssuer(definitions: CouponDefinitions, issuance: CouponIssuance): RewardIssuer = CouponRewardIssuer(definitions, issuance)

    @Bean
    @ConditionalOnBean(CouponStore::class)
    @ConditionalOnMissingBean(name = ["couponDiscountSource"])
    fun couponDiscountSource(store: CouponStore, time: ObjectProvider<TimeProvider>): DiscountSource =
        CouponDiscountSource(store, time.getIfAvailable { TimeProvider.systemUtc() })

    @Bean
    @ConditionalOnBean(CouponStore::class)
    @ConditionalOnMissingBean(name = ["couponAccountErasureListener"])
    fun couponAccountErasureListener(store: CouponStore): AccountErasureListener = CouponAccountErasureListener(store)
}
