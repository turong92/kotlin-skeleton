package dev.sumin.skeleton.video.cloudrun

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.video.VideoWakeException
import dev.sumin.skeleton.video.VideoWakeRuns
import dev.sumin.skeleton.video.VideoWaker
import dev.sumin.skeleton.video.WakeTicket
import java.time.Duration
import org.slf4j.LoggerFactory

/** 연속 실패가 문턱에 닿았을 때 부른다 - [failures] 번째 연속 실패, [reason] 은 상태 코드 · 예외 종류만 든 문장 */
fun interface WakeFailureAlert {
    fun failed(failures: Int, reason: String)
}

/**
 * 깨우기에 **돈 상한**을 두른다: 창 안에 받아들여진 깨우기가 상한이면, 마지막 깨우기에서 최소 간격이 안 지났으면 구글을 부르지 않고 조용히 넘어간다
 * (이미 도는 실행이 큐가 빌 때까지 집으니 일은 안 잃는다). 실패는 기록하고 던진다 - `video.wake` 작업이 백오프로 다시 한다.
 * 연속 [CloudRunProperties.alertAfterFailures] 번이면 [alert]. **자동으로 다른 곳으로 넘기지 않는다** - 일은 큐에 남고 경보가 간다
 */
class GuardedWaker(
    private val inner: VideoWaker,
    private val runs: VideoWakeRuns,
    private val config: CloudRunProperties,
    private val alert: WakeFailureAlert,
    private val time: TimeProvider,
) : VideoWaker {
    private val log = LoggerFactory.getLogger(javaClass)

    override val enabled: Boolean get() = inner.enabled

    override fun wake(): WakeTicket {
        val now = time.now()
        if (runs.okSince(now.minus(config.executionWindow)) >= config.maxExecutions) {
            log.debug("video wake skipped: {} executions already started in {}", config.maxExecutions, config.executionWindow)
            return WakeTicket(null)
        }
        val last = runs.lastOkAt()
        if (last != null && now < last.plus(config.minWakeGap)) {
            log.debug("video wake skipped: last wake was less than {} ago", config.minWakeGap)
            return WakeTicket(null)
        }
        try {
            val ticket = inner.wake()
            runs.record(now, true, ticket.execution, null)
            runs.deleteOlderThan(now.minus(RETENTION))
            return ticket
        } catch (e: VideoWakeException) {
            runs.record(now, false, null, e.message)
            val failures = runs.consecutiveFailures()
            log.warn("video worker wake failed {} time(s) in a row: {}", failures, e.message)
            if (failures >= config.alertAfterFailures) alert.failed(failures, e.message ?: "unknown")
            throw e
        }
    }

    private companion object {
        val RETENTION: Duration = Duration.ofDays(7)
    }
}
