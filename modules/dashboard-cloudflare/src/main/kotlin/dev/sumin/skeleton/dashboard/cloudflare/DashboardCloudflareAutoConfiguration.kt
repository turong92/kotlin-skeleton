package dev.sumin.skeleton.dashboard.cloudflare

import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.dashboard.DailySeries
import dev.sumin.skeleton.dashboard.DashboardAutoConfiguration
import dev.sumin.skeleton.dashboard.DashboardCache
import dev.sumin.skeleton.dashboard.DashboardIngestor
import dev.sumin.skeleton.dashboard.DashboardRangeParser
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * Cloudflare 카드(방문 · 페이지뷰 타일, 유입 · 페이지 · 국가 · 기기 순위)와 일별 적재기. 키가 없어도 서고 기동은 막지 않는다 — 카드가 `NOT_CONNECTED` 로 말하고 적재기는 아무것도 하지 않는다.
 */
@AutoConfiguration(after = [DashboardAutoConfiguration::class])
@EnableConfigurationProperties(CloudflareProperties::class)
class DashboardCloudflareAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun cloudflareSettings(properties: CloudflareProperties, projects: ObjectProvider<ProjectRegistry>): CloudflareSettings = CloudflareSettings(properties, projects.getIfAvailable())

    @Bean
    @ConditionalOnMissingBean
    fun cloudflareSource(http: ExternalHttpClient, settings: CloudflareSettings, series: DailySeries, cache: DashboardCache, parser: DashboardRangeParser): CloudflareSource =
        CloudflareSource(CloudflareAnalyticsClient(http), settings, series, cache, parser::zone)

    @Bean
    @ConditionalOnMissingBean(name = ["cloudflareIngestor"])
    fun cloudflareIngestor(source: CloudflareSource): DashboardIngestor = CloudflareIngestor(source)

    @Bean
    @ConditionalOnMissingBean(name = ["cloudflareVisitsMetric"])
    fun cloudflareVisitsMetric(source: CloudflareSource) = CloudflareMetric("cloudflare.visits", "Visits", 10, CloudflareKinds.VISITS, source)

    @Bean
    @ConditionalOnMissingBean(name = ["cloudflarePageviewsMetric"])
    fun cloudflarePageviewsMetric(source: CloudflareSource) = CloudflareMetric("cloudflare.pageviews", "Page views", 11, CloudflareKinds.PAGEVIEWS, source)

    @Bean
    @ConditionalOnMissingBean(name = ["cloudflareReferrersRanking"])
    fun cloudflareReferrersRanking(source: CloudflareSource) =
        CloudflareRanking("cloudflare.referrers", "traffic", "Sources", listOf("Source", "Visits"), 10, CloudflareKinds.REFERRER, CloudflareAnalyticsClient.Grouping.REFERRER, source)

    @Bean
    @ConditionalOnMissingBean(name = ["cloudflarePathsRanking"])
    fun cloudflarePathsRanking(source: CloudflareSource) =
        CloudflareRanking("cloudflare.paths", "pages", "Top pages", listOf("Page", "Views"), 10, CloudflareKinds.PATH, CloudflareAnalyticsClient.Grouping.PATH, source)

    @Bean
    @ConditionalOnMissingBean(name = ["cloudflareCountriesRanking"])
    fun cloudflareCountriesRanking(source: CloudflareSource) =
        CloudflareRanking("cloudflare.countries", "geo", "Countries", listOf("Country", "Visits"), 10, CloudflareKinds.COUNTRY, CloudflareAnalyticsClient.Grouping.COUNTRY, source)

    @Bean
    @ConditionalOnMissingBean(name = ["cloudflareDevicesRanking"])
    fun cloudflareDevicesRanking(source: CloudflareSource) =
        CloudflareRanking("cloudflare.devices", "geo", "Devices", listOf("Device", "Visits"), 20, CloudflareKinds.DEVICE, CloudflareAnalyticsClient.Grouping.DEVICE, source)
}
