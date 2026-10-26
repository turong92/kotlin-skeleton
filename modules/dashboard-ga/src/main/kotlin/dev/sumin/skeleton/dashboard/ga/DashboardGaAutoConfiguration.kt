package dev.sumin.skeleton.dashboard.ga

import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.dashboard.DailySeries
import dev.sumin.skeleton.dashboard.DashboardAutoConfiguration
import dev.sumin.skeleton.dashboard.DashboardCache
import dev.sumin.skeleton.dashboard.DashboardIngestor
import dev.sumin.skeleton.dashboard.ga.web.DashboardRealtimeController
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * GA 카드(채널 · 캠페인 · 첫 페이지 · 페이지 이동 · 재방문 순위)와 일별 적재기. 키가 없어도 서고 기동은 막지 않는다 — 카드가 `NOT_CONNECTED` 로 말하고 적재기는 아무것도 하지 않는다.
 */
@AutoConfiguration(after = [DashboardAutoConfiguration::class])
@EnableConfigurationProperties(GaProperties::class)
class DashboardGaAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun gaSettings(properties: GaProperties, projects: ObjectProvider<ProjectRegistry>): GaSettings = GaSettings(properties, projects.getIfAvailable())

    @Bean
    @ConditionalOnMissingBean
    fun gaDataClient(http: ExternalHttpClient): GaDataClient = GaDataClient(http, GaTokenSource(http))

    @Bean
    @ConditionalOnMissingBean
    fun gaSource(client: GaDataClient, settings: GaSettings, series: DailySeries, cache: DashboardCache): GaSource = GaSource(client, settings, series, cache)

    @Bean
    @ConditionalOnMissingBean(name = ["gaIngestor"])
    fun gaIngestor(client: GaDataClient, settings: GaSettings): DashboardIngestor = GaIngestor(client, settings)

    @Bean
    @ConditionalOnMissingBean(name = ["gaChannelsRanking"])
    fun gaChannelsRanking(source: GaSource) = GaRanking("ga.channels", "traffic", "Channels", 30, "Channel", GaKinds.CHANNEL, source)

    @Bean
    @ConditionalOnMissingBean(name = ["gaCampaignsRanking"])
    fun gaCampaignsRanking(source: GaSource) = GaRanking("ga.campaigns", "traffic", "Campaigns", 31, "Campaign", GaKinds.CAMPAIGN, source)

    @Bean
    @ConditionalOnMissingBean(name = ["gaLandingPagesRanking"])
    fun gaLandingPagesRanking(source: GaSource) = GaRanking("ga.landing-pages", "pages", "Landing pages", 30, "Page", GaKinds.LANDING, source)

    @Bean
    @ConditionalOnMissingBean(name = ["gaPageTransitionsRanking"])
    fun gaPageTransitionsRanking(source: GaSource) = GaRanking("ga.page-transitions", "pages", "Page flow", 31, "From → To", GaKinds.TRANSITION, source, conversions = false)

    @Bean
    @ConditionalOnMissingBean(name = ["gaReturningRanking"])
    fun gaReturningRanking(source: GaSource) = GaReturningRanking(40, source)
}

/** 현재 방문자 컨트롤러 — `skeleton.dashboard.admin.enabled=true`, 서블릿 웹 앱, Spring Security(호출자)가 있을 때만 */
@AutoConfiguration(after = [DashboardGaAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["org.springframework.security.core.Authentication"])
@ConditionalOnProperty(prefix = "skeleton.dashboard.admin", name = ["enabled"], havingValue = "true")
class DashboardGaWebAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun dashboardRealtimeController(source: GaSource, access: OperatorAccess): DashboardRealtimeController = DashboardRealtimeController(source, access)
}
