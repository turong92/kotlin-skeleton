package dev.sumin.skeleton.dashboard

import dev.sumin.skeleton.common.authz.AuthzAutoConfiguration
import dev.sumin.skeleton.common.authz.PermissionDeclaration
import dev.sumin.skeleton.common.dashboard.DashboardCohortSource
import dev.sumin.skeleton.common.dashboard.DashboardFunnel
import dev.sumin.skeleton.common.dashboard.DashboardFunnelStep
import dev.sumin.skeleton.common.dashboard.DashboardMetric
import dev.sumin.skeleton.common.dashboard.DashboardRanking
import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.common.time.TimeProvider
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment

/**
 * 기여 빈(`DashboardMetric` · `DashboardRanking` · 퍼널)을 모아 조립하는 부품. 권한 `dashboard.read` 선언은 모듈이 클래스패스에 있으면 늘 낸다 (스위치와 무관 — 카탈로그와 yml 매핑 검증이 본다).
 * 관리 API 는 [DashboardWebAutoConfiguration].
 *
 * 퍼널은 앱이 `DashboardFunnel` 빈을 두면 그것이, 없으면 `DashboardCohortSource` 하나 + `DashboardFunnelStep` 들로 부를 때마다 만든다 —
 * 기여 모듈의 자동 설정과 등록 순서를 따지지 않으려고 빈 조건이 아니라 조립 때 본다.
 */
@AutoConfiguration(after = [AuthzAutoConfiguration::class])
@EnableConfigurationProperties(DashboardProperties::class)
class DashboardAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = ["dashboardPermissionDeclaration"])
    fun dashboardPermissionDeclaration(): PermissionDeclaration = DashboardPermissions.declaration

    @Bean
    @ConditionalOnMissingBean
    fun dashboardSettings(properties: DashboardProperties, environment: Environment, projects: ObjectProvider<ProjectRegistry>): DashboardSettings =
        DashboardSettings(properties, environment.getProperty("skeleton.time.default-zone", DEFAULT_ZONE), projects.getIfAvailable())

    @Bean
    @ConditionalOnMissingBean
    fun dashboardRangeParser(settings: DashboardSettings, time: TimeProvider): DashboardRangeParser = DashboardRangeParser(settings::zone, time::now)

    @Bean
    @ConditionalOnMissingBean
    fun dashboardCache(properties: DashboardProperties, time: TimeProvider): DashboardCache =
        DashboardCache(properties.cache.db, now = time::now)

    @Bean
    @ConditionalOnMissingBean
    fun dashboardAssembler(
        properties: DashboardProperties,
        settings: DashboardSettings,
        cache: DashboardCache,
        metrics: ObjectProvider<DashboardMetric>,
        rankings: ObjectProvider<DashboardRanking>,
        funnel: ObjectProvider<DashboardFunnel>,
        cohorts: ObjectProvider<DashboardCohortSource>,
        steps: ObjectProvider<DashboardFunnelStep>,
    ): DashboardAssembler = DashboardAssembler(
        metrics = { metrics.orderedStream().toList() },
        rankings = { rankings.orderedStream().toList() },
        funnel = {
            funnel.getIfAvailable() ?: cohorts.orderedStream().findFirst().map { DashboardFunnel(it, steps.orderedStream().toList()) }.orElse(null)
        },
        cache = cache,
        currency = settings::currency,
        funnelTtl = properties.cache.funnel,
        maxCohort = properties.funnel.maxCohort,
    )

    @Bean
    @ConditionalOnMissingBean
    fun dashboardDailySeries(store: ObjectProvider<DashboardDailyStore>, parser: DashboardRangeParser, time: TimeProvider): DailySeries =
        DailySeries({ store.getIfAvailable() }, parser::zone, time::now)

    /** 로그만 남기는 기본 — `alert` 가 있으면 [DashboardAlertAutoConfiguration] 의 것이 먼저 서서 이쪽이 물러난다 */
    @Bean
    @ConditionalOnMissingBean
    fun dashboardIngestReporter(): DashboardIngestReporter = DashboardIngestReporter { _, _ -> }

    @Bean
    @ConditionalOnMissingBean
    fun dashboardIngestRunner(
        properties: DashboardProperties,
        time: TimeProvider,
        store: ObjectProvider<DashboardDailyStore>,
        ingestors: ObjectProvider<DashboardIngestor>,
        projects: ObjectProvider<ProjectRegistry>,
        settings: DashboardSettings,
        reporter: DashboardIngestReporter,
    ): DashboardIngestRunner = DashboardIngestRunner(
        ingestors = { ingestors.orderedStream().toList() },
        store = { store.getIfAvailable() },
        zone = settings::zone,
        properties = properties.ingest,
        projects = projects.getIfAvailable(),
        reporter = reporter,
        now = time::now,
    )

    private companion object {
        /** `time` 모듈의 `skeleton.time.default-zone` 코드 기본값과 같다 — 둘 다 안 주면 앱 시간대와 대시보드 하루 경계가 어긋나지 않는다 */
        const val DEFAULT_ZONE = "Asia/Seoul"
    }
}
