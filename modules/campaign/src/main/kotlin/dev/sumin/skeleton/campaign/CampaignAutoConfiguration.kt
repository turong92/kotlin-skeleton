package dev.sumin.skeleton.campaign

import dev.sumin.skeleton.campaign.erasure.CampaignAccountErasureListener
import dev.sumin.skeleton.common.account.AccountFacts
import dev.sumin.skeleton.common.authz.PermissionDeclaration
import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.reward.RewardIssuer
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.reward.RewardAutoConfiguration
import dev.sumin.skeleton.reward.RewardGrants
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment

/**
 * 이벤트(출석 · 초대)와 그 서비스. 저장소 포트([CampaignStore])는 `campaign-jdbc`, 지급 기록([RewardGrants])은 `reward`(+`reward-jdbc`)가 낸다 -
 * 둘 중 하나라도 없으면 서비스 빈이 켜지지 않는다. 쿠폰 · 포인트를 모른다: 지급기가 하나도 없어도 뜨고(건은 `FAILED` 로 남는다), 보상 종류는 설정에 이름으로만 적힌다.
 * [AccountFacts](`account` 가 낸다)가 없으면 초대의 「새 계정만」 판정을 건너뛴다.
 */
@AutoConfiguration(
    after = [RewardAutoConfiguration::class],
    afterName = ["dev.sumin.skeleton.campaign.jdbc.CampaignJdbcAutoConfiguration", "dev.sumin.skeleton.reward.jdbc.RewardJdbcAutoConfiguration"],
)
@EnableConfigurationProperties(CampaignProperties::class)
class CampaignAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = ["campaignPermissionDeclaration"])
    fun campaignPermissionDeclaration(): PermissionDeclaration = CampaignPermissions.declaration()

    @Bean
    @ConditionalOnMissingBean(name = ["attendanceKind"])
    fun attendanceKind(): CampaignKind = AttendanceKind()

    @Bean
    @ConditionalOnMissingBean(name = ["referralKind"])
    fun referralKind(): CampaignKind = ReferralKind()

    @Bean
    @ConditionalOnMissingBean
    fun campaignZone(properties: CampaignProperties, environment: Environment): CampaignZone =
        CampaignZone.resolve(properties.zone, environment.getProperty("skeleton.time.default-zone"))

    @Bean
    @ConditionalOnBean(CampaignStore::class)
    @ConditionalOnMissingBean
    fun campaigns(store: CampaignStore, kinds: ObjectProvider<CampaignKind>, issuers: ObjectProvider<RewardIssuer>, time: ObjectProvider<TimeProvider>): Campaigns =
        Campaigns(store, { kinds.orderedStream().toList() }, { issuers.orderedStream().toList() }, time.getIfAvailable { TimeProvider.systemUtc() })

    @Bean
    @ConditionalOnBean(CampaignStore::class, RewardGrants::class)
    @ConditionalOnMissingBean
    fun attendanceService(
        store: CampaignStore,
        grants: RewardGrants,
        issuers: ObjectProvider<RewardIssuer>,
        zone: CampaignZone,
        time: ObjectProvider<TimeProvider>,
    ): AttendanceService = AttendanceService(store, grants, { issuers.orderedStream().toList() }, zone, time.getIfAvailable { TimeProvider.systemUtc() })

    @Bean
    @ConditionalOnBean(Campaigns::class, RewardGrants::class)
    @ConditionalOnMissingBean
    fun referralService(
        store: CampaignStore,
        campaigns: Campaigns,
        grants: RewardGrants,
        facts: ObjectProvider<AccountFacts>,
        time: ObjectProvider<TimeProvider>,
    ): ReferralService = ReferralService(store, campaigns, grants, facts.getIfAvailable(), time.getIfAvailable { TimeProvider.systemUtc() })

    /** 앱이 부르는 자리 - 앱이 같은 타입의 빈을 두면 그것이 이긴다 */
    @Bean
    @ConditionalOnBean(ReferralService::class)
    @ConditionalOnMissingBean(Referrals::class)
    fun referrals(service: ReferralService): Referrals = service

    @Bean
    @ConditionalOnBean(CampaignStore::class)
    @ConditionalOnMissingBean(name = ["campaignAccountErasureListener"])
    fun campaignAccountErasureListener(store: CampaignStore): AccountErasureListener = CampaignAccountErasureListener(store)
}
