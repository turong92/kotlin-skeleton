package dev.sumin.skeleton.dashboard

import dev.sumin.skeleton.common.project.ProjectRegistry
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner

/** 적재 실패를 주인에게 알리는 자리 — `alert` 가 있으면 알림으로 이어진다 (기본은 로그뿐). 예외 메시지는 넘기지 않는다: 비밀이 있을 수 있다 */
fun interface DashboardIngestReporter {
    fun failed(source: String, error: Exception)
}

/**
 * 외부 출처의 하루치를 일별 표에 옮겨 적는 자체 주기 작업. 기동 직후 한 번, 그다음 매일 `ingest.at`(대시보드 시간대)에 어제부터 `backfill-days` 일을 다시 적재한다.
 * 프로젝트 모드에서는 프로젝트마다(저장소가 그 프로젝트의 스키마를 본다). 적재기 하나가 실패해도 다른 적재기는 돌고 기동은 막지 않는다.
 * 여러 인스턴스가 같은 시각에 돌 수 있다 — 적재는 같은 날을 다시 써도 같은 행이라(upsert) 결과가 같고, 외부 호출이 한 번 더 나갈 뿐이다.
 */
class DashboardIngestRunner(
    private val ingestors: () -> List<DashboardIngestor>,
    private val store: () -> DashboardDailyStore?,
    private val zone: () -> ZoneId,
    private val properties: DashboardProperties.Ingest,
    private val projects: ProjectRegistry?,
    private val reporter: DashboardIngestReporter,
    private val now: () -> Instant = Instant::now,
) : ApplicationRunner, DisposableBean {
    private val log = LoggerFactory.getLogger(javaClass)
    private var executor: ScheduledExecutorService? = null

    override fun run(args: ApplicationArguments) {
        if (!properties.enabled || store() == null || ingestors().isEmpty()) return
        val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "dashboard-ingest").apply { isDaemon = true } }
        executor = scheduler
        schedule(scheduler, Duration.ZERO)
    }

    private fun schedule(scheduler: ScheduledExecutorService, delay: Duration) {
        scheduler.schedule({
            try {
                runOnce()
            } catch (e: Exception) {
                log.warn("dashboard ingest run failed: {}", e.javaClass.simpleName)
            } finally {
                if (!scheduler.isShutdown) schedule(scheduler, untilNextRun())
            }
        }, delay.toMillis(), TimeUnit.MILLISECONDS)
    }

    private fun untilNextRun(): Duration {
        val zone = zone()
        val current = now().atZone(zone)
        var next = current.toLocalDate().atTime(properties.at).atZone(zone)
        if (!next.isAfter(current)) next = current.toLocalDate().plusDays(1).atTime(properties.at).atZone(zone)
        return Duration.between(current, next)
    }

    /** 모든 프로젝트의 모든 적재기를 한 번 돈다 — 어제부터 거슬러 `backfill-days` 일 */
    fun runOnce() {
        val target = store() ?: return
        val list = ingestors()
        if (projects == null) ingestAll(target, list) else projects.forEachProject { ingestAll(target, list) }
    }

    /** 프로젝트마다 그 프로젝트의 시간대로 「어제」를 센다 */
    private fun ingestAll(target: DashboardDailyStore, list: List<DashboardIngestor>) {
        val zone = zone()
        val today = now().atZone(zone).toLocalDate()
        val days = (properties.backfillDays.coerceAtLeast(1) downTo 1).map { today.minusDays(it.toLong()) }
        list.forEach { ingestor ->
            var failure: Exception? = null
            days.forEach { day ->
                try {
                    if (!ingestor.active()) return@forEach
                    target.replace(day, ingestor.source, ingestor.kinds, ingestor.ingest(day, zone))
                } catch (e: Exception) {
                    failure = failure ?: e
                }
            }
            failure?.let {
                log.warn("dashboard ingest of {} failed: {}", ingestor.source, it.javaClass.simpleName)
                reporter.failed(ingestor.source, it)
            }
        }
    }

    override fun destroy() {
        executor?.shutdownNow()
    }
}
