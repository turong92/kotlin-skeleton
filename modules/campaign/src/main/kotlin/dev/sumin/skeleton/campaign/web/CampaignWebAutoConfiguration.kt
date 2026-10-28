package dev.sumin.skeleton.campaign.web

import dev.sumin.skeleton.campaign.AttendanceService
import dev.sumin.skeleton.campaign.CampaignAutoConfiguration
import dev.sumin.skeleton.campaign.Campaigns
import dev.sumin.skeleton.campaign.ReferralService
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean

/**
 * HTTP 경로 - 서블릿 웹 앱이고 Spring Security(호출자)가 클래스패스에 있을 때만. 사용자 경로(출석 · 초대)는 `skeleton.campaign.http.enabled=false` 로 끄고,
 * 운영자 경로는 `skeleton.campaign.admin.enabled=true` 일 때만 열린다(기본 꺼짐 → 404). 인증은 앱의 보안 설정이 건다.
 */
@AutoConfiguration(after = [CampaignAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["org.springframework.security.core.Authentication"])
class CampaignWebAutoConfiguration {
    @Bean
    @ConditionalOnBean(Campaigns::class, AttendanceService::class)
    @ConditionalOnProperty(prefix = "skeleton.campaign.http", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    fun campaignController(campaigns: Campaigns, attendance: AttendanceService): CampaignController = CampaignController(campaigns, attendance)

    @Bean
    @ConditionalOnBean(ReferralService::class)
    @ConditionalOnProperty(prefix = "skeleton.campaign.http", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    fun referralController(referrals: ReferralService): ReferralController = ReferralController(referrals)

    @Bean
    @ConditionalOnBean(Campaigns::class)
    @ConditionalOnProperty(prefix = "skeleton.campaign.admin", name = ["enabled"], havingValue = "true")
    @ConditionalOnMissingBean
    fun campaignAdminController(campaigns: Campaigns, access: OperatorAccess, audit: OperatorAuditLog): CampaignAdminController =
        CampaignAdminController(campaigns, access, audit)
}
