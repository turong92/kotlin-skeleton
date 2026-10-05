package dev.sumin.skeleton.jobqueue.jdbc

import dev.sumin.skeleton.common.time.TimeProvider
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory
import org.slf4j.MDC

/**
 * 폴링 워커. [JobQueueProperties.pollInterval] 마다 [JdbcJobRepository.claim] → 핸들러 실행 → DONE / 재시도(백오프) / DEAD.
 *
 * - 단일 인스턴스면 그냥 돌고, 여러 인스턴스면 SKIP LOCKED 가 분배한다 (Redis 락 없음)
 * - 핸들러 실행은 claim 트랜잭션 **밖** — 긴 작업이 행 락을 오래 잡지 않게
 * - 워커가 죽어 RUNNING 으로 남은 잡은 [JobQueueProperties.staleLockTimeout] 뒤 PENDING 으로 복구 (시도를 다 썼으면 DEAD)
 * - 종료 갱신은 임대 주인(`locked_by` · `attempts`)일 때만 반영하고, 묶음의 각 잡은 차례가 올 때 임대를 새로 잡는다
 * - 돌리는 동안 넣은 쪽의 MDC 문맥을 복원하고, DEAD 가 되는 잡은 [JobDeadListener] 에 알린다
 */
class JobQueueWorker(
    private val repository: JdbcJobRepository,
    private val handlers: List<JobHandler>,
    private val properties: JobQueueProperties,
    private val timeProvider: TimeProvider,
    private val propagator: JobContextPropagator = MdcJobContextPropagator(properties.propagatedMdcKeys),
    private val deadListeners: List<JobDeadListener> = emptyList(),
    private val retention: JobRetention? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val handlerByType = handlers.associateBy { it.type }
    val workerId: String = properties.workerId.ifBlank { defaultWorkerId() }
    private val running = AtomicBoolean(false)
    private var executor: ScheduledExecutorService? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "skeleton-job-queue").apply { isDaemon = true } }
            .also { it.scheduleWithFixedDelay(::safePoll, properties.pollInterval.toMillis(), properties.pollInterval.toMillis(), TimeUnit.MILLISECONDS) }
        log.info("Job queue worker started: workerId={} pollInterval={} batchSize={}", workerId, properties.pollInterval, properties.batchSize)
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        executor?.shutdownNow()
        executor = null
    }

    private fun safePoll() {
        try {
            pollOnce()
        } catch (ex: Exception) {
            log.error("Job queue poll failed", ex)
        }
    }

    /** 한 번 폴링. 테스트에서 직접 호출할 수 있다. 처리한 잡 수를 돌려준다. */
    fun pollOnce(): Int {
        val now = timeProvider.now()
        val stale = repository.recoverStale(now.minus(properties.staleLockTimeout), now)
        if (stale.recovered > 0) log.warn("Recovered {} stale RUNNING job(s)", stale.recovered)
        stale.dead.forEach { job ->
            // 워커가 죽은 채 시도를 다 쓴 잡 — 복구를 돌리는 이 워커가 대신 알린다 (독 작업이 조용히 DEAD 로 묻히지 않게)
            log.warn("Job {} ({}) was stale RUNNING with all {} attempts used, marked DEAD", job.id, job.type, job.maxAttempts)
            notifyDead(job, job.lastError ?: "stale RUNNING job exhausted its attempts")
        }
        runRetention()
        val jobs = repository.claim(workerId, properties.batchSize, now)
        jobs.forEach { job ->
            // 차례가 온 잡의 임대를 새로 잡는다 — 못 잡으면 앞 잡이 오래 걸려 그 사이 복구되어 남에게 넘어갔다. 돌리지 않는다
            if (repository.renew(job.id, workerId, job.attempts, timeProvider.now())) run(job)
            else log.warn("Job {} ({}) was reclaimed by another worker before its turn, skipped", job.id, job.type)
        }
        return jobs.size
    }

    private fun runRetention() {
        try {
            retention?.runIfDue()?.let { if (it.done + it.dead > 0) log.info("Job retention purged done={} dead={}", it.done, it.dead) }
        } catch (ex: Exception) {
            log.warn("Job retention failed: {}", ex.javaClass.simpleName) // 정리가 실패해도 잡 처리는 계속
        }
    }

    /** 돌리는 동안 넣은 쪽의 문맥(traceId 등)과 jobId · jobKind 를 MDC 에 둔다 — 로그 한 줄로 요청에서 작업까지 이어진다. 끝나면 원래대로 */
    private fun run(job: Job) {
        propagator.restore(job.logContext).use {
            MDC.put("jobId", job.id.toString())
            MDC.put("jobKind", job.type)
            try {
                runHandler(job)
            } finally {
                MDC.remove("jobId")
                MDC.remove("jobKind")
            }
        }
    }

    private fun runHandler(job: Job) {
        val handler = handlerByType[job.type]
        if (handler == null) {
            val reason = "No handler registered for job type '${job.type}'"
            if (repository.markDead(job.id, workerId, job.attempts, reason, timeProvider.now())) notifyDead(job, reason) else logLostLease(job)
            log.error("No handler for job type '{}' (job {}), marked DEAD", job.type, job.id)
            return
        }
        try {
            handler.handle(job)
            if (!repository.markDone(job.id, workerId, job.attempts, timeProvider.now())) logLostLease(job)
        } catch (ex: PermanentJobFailureException) {
            if (repository.markDead(job.id, workerId, job.attempts, describe(ex), timeProvider.now())) notifyDead(job, describe(ex)) else logLostLease(job)
            log.warn("Job {} ({}) failed permanently: {}", job.id, job.type, ex.message)
        } catch (ex: Exception) {
            val now = timeProvider.now()
            if (job.attempts >= job.maxAttempts) {
                if (repository.markDead(job.id, workerId, job.attempts, describe(ex), now)) notifyDead(job, describe(ex)) else logLostLease(job)
                log.error("Job {} ({}) dead after {} attempts", job.id, job.type, job.attempts, ex)
            } else {
                val next = now.plus(properties.backoff.delayAfter(job.attempts))
                if (!repository.markRetry(job.id, workerId, job.attempts, next, describe(ex), now)) logLostLease(job)
                log.warn("Job {} ({}) failed (attempt {}/{}), retry at {}: {}", job.id, job.type, job.attempts, job.maxAttempts, next, ex.message)
            }
        }
    }

    /** DEAD 로 확정된 잡만(임대를 잃은 쪽은 알리지 않는다) — 듣는 쪽이 터져도 워커는 계속 돈다 */
    private fun notifyDead(job: Job, reason: String) {
        deadListeners.forEach { listener ->
            try {
                listener.onDead(job, reason)
            } catch (ex: Exception) {
                log.warn("Dead-job listener {} failed for job {}: {}", listener.javaClass.simpleName, job.id, ex.javaClass.simpleName)
            }
        }
    }

    /** 끝낸 사이 임대가 남에게 넘어갔다 — 그쪽 결과가 이긴다 (핸들러는 멱등이어야 한다) */
    private fun logLostLease(job: Job) =
        log.warn("Job {} ({}) lease was lost while running (attempt {}); the result is ignored", job.id, job.type, job.attempts)

    private fun describe(ex: Throwable) = "${ex::class.java.name}: ${ex.message}"

    private fun defaultWorkerId(): String {
        val host = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("worker")
        return "$host-${UUID.randomUUID().toString().take(8)}"
    }
}
