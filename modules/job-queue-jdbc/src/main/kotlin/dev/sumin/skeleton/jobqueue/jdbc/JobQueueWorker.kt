package dev.sumin.skeleton.jobqueue.jdbc

import dev.sumin.skeleton.common.time.TimeProvider
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory

/**
 * 폴링 워커. [JobQueueProperties.pollInterval] 마다 [JdbcJobRepository.claim] → 핸들러 실행 → DONE / 재시도(백오프) / DEAD.
 *
 * - 단일 인스턴스면 그냥 돌고, 여러 인스턴스면 SKIP LOCKED 가 분배한다 (Redis 락 없음)
 * - 핸들러 실행은 claim 트랜잭션 **밖** — 긴 작업이 행 락을 오래 잡지 않게
 * - 워커가 죽어 RUNNING 으로 남은 잡은 [JobQueueProperties.staleLockTimeout] 뒤 PENDING 으로 복구
 */
class JobQueueWorker(
    private val repository: JdbcJobRepository,
    private val handlers: List<JobHandler>,
    private val properties: JobQueueProperties,
    private val timeProvider: TimeProvider,
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
        val recovered = repository.recoverStale(now.minus(properties.staleLockTimeout), now)
        if (recovered > 0) log.warn("Recovered {} stale RUNNING job(s)", recovered)
        val jobs = repository.claim(workerId, properties.batchSize, now)
        jobs.forEach(::run)
        return jobs.size
    }

    private fun run(job: Job) {
        val handler = handlerByType[job.type]
        if (handler == null) {
            repository.markDead(job.id, "No handler registered for job type '${job.type}'", timeProvider.now())
            log.error("No handler for job type '{}' (job {}), marked DEAD", job.type, job.id)
            return
        }
        try {
            handler.handle(job)
            repository.markDone(job.id, timeProvider.now())
        } catch (ex: PermanentJobFailureException) {
            repository.markDead(job.id, describe(ex), timeProvider.now())
            log.warn("Job {} ({}) failed permanently: {}", job.id, job.type, ex.message)
        } catch (ex: Exception) {
            val now = timeProvider.now()
            if (job.attempts >= job.maxAttempts) {
                repository.markDead(job.id, describe(ex), now)
                log.error("Job {} ({}) dead after {} attempts", job.id, job.type, job.attempts, ex)
            } else {
                val next = now.plus(properties.backoff.delayAfter(job.attempts))
                repository.markRetry(job.id, next, describe(ex), now)
                log.warn("Job {} ({}) failed (attempt {}/{}), retry at {}: {}", job.id, job.type, job.attempts, job.maxAttempts, next, ex.message)
            }
        }
    }

    private fun describe(ex: Throwable) = "${ex::class.java.name}: ${ex.message}"

    private fun defaultWorkerId(): String {
        val host = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("worker")
        return "$host-${UUID.randomUUID().toString().take(8)}"
    }
}
