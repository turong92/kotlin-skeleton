package dev.sumin.skeleton.dashboard

import dev.sumin.skeleton.alert.AlertKind
import dev.sumin.skeleton.alert.AlertSeverity
import dev.sumin.skeleton.alert.OwnerAlerts
import java.time.Duration
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/** 적재 실패의 알림 종류 — 같은 출처는 하루에 한 번만 알린다 */
object DashboardIngestFailed : AlertKind {
    override val name = "DASHBOARD_INGEST_FAILED"
    override val severity = AlertSeverity.WARN
    override val title = "A dashboard daily load failed"
    override val minInterval: Duration = Duration.ofHours(24)
}

/** `alert` 가 클래스패스에 있으면 적재 실패를 주인에게 알린다 (없으면 이 클래스는 읽히지도 않는다). 알림에는 출처 이름과 예외 종류만 싣는다 */
@AutoConfiguration(before = [DashboardAutoConfiguration::class], afterName = ["dev.sumin.skeleton.alert.AlertAutoConfiguration"])
@ConditionalOnClass(name = ["dev.sumin.skeleton.alert.OwnerAlerts"])
class DashboardAlertAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(OwnerAlerts::class)
    fun dashboardIngestReporter(alerts: OwnerAlerts): DashboardIngestReporter =
        DashboardIngestReporter { source, error -> alerts.emit(DashboardIngestFailed, key = source, detail = "${error.javaClass.simpleName} while loading $source") }
}
