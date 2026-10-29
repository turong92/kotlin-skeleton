package dev.sumin.skeleton.coupon.web

import dev.sumin.skeleton.common.account.AccountLabels
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.coupon.CouponAutoConfiguration
import dev.sumin.skeleton.coupon.CouponCodes
import dev.sumin.skeleton.coupon.CouponDefinitions
import dev.sumin.skeleton.coupon.CouponIssuance
import dev.sumin.skeleton.coupon.CouponReports
import dev.sumin.skeleton.coupon.CouponWallet
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean

/**
 * 사용자 경로(쿠폰함 · 코드 등록, `skeleton.coupon.http.enabled` 기본 켜짐)와 운영자 경로(`skeleton.coupon.admin.enabled=true` 일 때만, 꺼져 있으면 404).
 * 서블릿 웹 앱이고 Spring Security(호출자)가 클래스패스에 있을 때만.
 */
@AutoConfiguration(after = [CouponAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["org.springframework.security.core.Authentication"])
class CouponWebAutoConfiguration {
    @Bean
    @ConditionalOnBean(CouponWallet::class, CouponCodes::class)
    @ConditionalOnProperty(prefix = "skeleton.coupon.http", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    fun couponController(wallet: CouponWallet, codes: CouponCodes): CouponController = CouponController(wallet, codes)

    @Bean
    @ConditionalOnBean(CouponDefinitions::class, CouponIssuance::class, CouponCodes::class, CouponWallet::class, CouponReports::class)
    @ConditionalOnProperty(prefix = "skeleton.coupon.admin", name = ["enabled"], havingValue = "true")
    @ConditionalOnMissingBean
    fun couponAdminController(
        definitions: CouponDefinitions,
        issuance: CouponIssuance,
        codes: CouponCodes,
        wallet: CouponWallet,
        reports: CouponReports,
        access: OperatorAccess,
        audit: OperatorAuditLog,
        labels: ObjectProvider<AccountLabels>,
    ): CouponAdminController = CouponAdminController(definitions, issuance, codes, wallet, reports, access, audit, labels.getIfAvailable())
}
