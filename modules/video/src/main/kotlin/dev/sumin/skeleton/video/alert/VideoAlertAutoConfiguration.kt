package dev.sumin.skeleton.video.alert

import dev.sumin.skeleton.alert.AlertKind
import dev.sumin.skeleton.alert.AlertSeverity
import dev.sumin.skeleton.alert.OwnerAlerts
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.wake.OnRequestTask
import dev.sumin.skeleton.common.wake.SimpleOnRequestTask
import dev.sumin.skeleton.scheduler.SkeletonScheduled
import dev.sumin.skeleton.video.VideoAutoConfiguration
import dev.sumin.skeleton.video.VideoPorts
import dev.sumin.skeleton.video.VideoProperties
import dev.sumin.skeleton.video.VideoStore
import dev.sumin.skeleton.video.VideoWaker
import dev.sumin.skeleton.video.VideoWorkerStore
import java.time.Duration
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/** video 가 스스로 내는 경보 종류 — 같은 종류는 [minInterval] 안에 다시 와도 접힌다 */
enum class VideoAlertKind(override val severity: AlertSeverity, override val title: String, override val minInterval: Duration) : AlertKind {
    VIDEO_QUEUE_STALE(AlertSeverity.WARN, "No video worker is picking up the queue", Duration.ofMinutes(30)),
    VIDEO_WAKE_FAILED(AlertSeverity.ERROR, "Waking the video worker keeps failing", Duration.ofMinutes(30)),
    VIDEO_FAILED_SURGE(AlertSeverity.ERROR, "Video processing failures are piling up", Duration.ofMinutes(30)),
    VIDEO_COMPAT_STALE(AlertSeverity.WARN, "Compatible video tiers have waited a long time", Duration.ofHours(6)),
}

/**
 * 주인 경보 — 1분마다 점검한다(같은 종류는 간격이 접으니 매분 봐도 된다). 값 · 비밀은 싣지 않고 수 · 시각 · 설정 이름만.
 * `VIDEO_WAKE_FAILED` 는 깨우기 구현(`video-cloudrun`)이 연속 실패를 셀 때 내는 종류다 — 여기서는 이름만 낸다
 */
class VideoMonitor(
    private val props: VideoProperties,
    private val store: VideoStore,
    private val workers: VideoWorkerStore,
    private val waker: VideoWaker,
    private val alerts: OwnerAlerts,
    private val time: TimeProvider,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun check() {
        step("queue") { queueStale() }
        step("failures") { failedSurge() }
        step("compat") { compatStale() }
    }

    /** remux 일꾼을 [VideoProperties.Monitor.workerAbsent] 넘게 못 봤는데 대기가 있다 */
    private fun queueStale() {
        val queued = store.countQueuedRemux()
        if (queued == 0) return
        val now = time.now()
        val seen = workers.lastSeenRemux()
        if (seen != null && seen.isAfter(now.minus(props.monitor.workerAbsent))) return
        val since = if (seen == null) "has never been seen" else "has not been seen for ${Duration.between(seen, now).toMinutes()} minutes"
        alerts.emit(VideoAlertKind.VIDEO_QUEUE_STALE, detail = "No remux worker $since while $queued video(s) wait - check that the worker runs and its token is not revoked")
    }

    private fun failedSurge() {
        val cfg = props.monitor
        val failed = store.countFailedSince(time.now().minus(cfg.failureWindow))
        if (failed < cfg.failureSurgeCount) return
        alerts.emit(VideoAlertKind.VIDEO_FAILED_SURGE, detail = "$failed video failures in the last ${cfg.failureWindow.toMinutes()} minutes - check the worker log and the uploaded formats")
    }

    private fun compatStale() {
        val cfg = props.monitor
        val stale = store.countCompatQueuedBefore(time.now().minus(cfg.compatStaleAfter))
        if (stale == 0) return
        val hint = if (waker.enabled) "check that the waker runs" else "no waker is configured, so a resident worker with the COMPAT lane must be running"
        alerts.emit(VideoAlertKind.VIDEO_COMPAT_STALE, detail = "$stale video(s) have waited for a compatible tier for over ${cfg.compatStaleAfter.toHours()} hours - $hint")
    }

    private fun step(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log.warn("video monitor step failed: {} - {}", what, e.javaClass.simpleName)
        }
    }
}

/** 1분마다 [VideoMonitor] 를 돈다. `scheduler` 가 있을 때 돈다 */
class VideoMonitorJob(private val monitor: VideoMonitor) {
    @SkeletonScheduled(id = "video.monitor", fixedDelay = 60_000, initialDelay = 60_000, lockKey = "video.monitor")
    fun run() {
        monitor.check()
    }
}

/** `alert` 모듈이 있으면(`OwnerAlerts` 빈) 영상 경보 셋을 낸다. video 는 alert 를 `compileOnly` 로만 안다 */
@AutoConfiguration(after = [VideoAutoConfiguration::class], afterName = ["dev.sumin.skeleton.alert.AlertAutoConfiguration"])
@ConditionalOnClass(name = ["dev.sumin.skeleton.alert.OwnerAlerts"])
@ConditionalOnBean(VideoPorts::class, OwnerAlerts::class)
class VideoAlertAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun videoMonitor(props: VideoProperties, ports: VideoPorts, waker: VideoWaker, alerts: OwnerAlerts, time: TimeProvider): VideoMonitor =
        VideoMonitor(props, ports.store, ports.workerStore, waker, alerts, time)

    @Bean
    @ConditionalOnMissingBean(name = ["videoMonitorTask"])
    fun videoMonitorTask(monitor: VideoMonitor): OnRequestTask = SimpleOnRequestTask("video-monitor") { monitor.check() }

    @Bean
    @ConditionalOnClass(name = ["dev.sumin.skeleton.scheduler.SkeletonScheduled"])
    @ConditionalOnMissingBean
    fun videoMonitorJob(monitor: VideoMonitor): VideoMonitorJob = VideoMonitorJob(monitor)
}
