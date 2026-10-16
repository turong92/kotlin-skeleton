package dev.sumin.skeleton.jobqueue.jdbc

import dev.sumin.skeleton.common.project.Project
import dev.sumin.skeleton.common.project.ProjectContext
import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.common.time.TimeProvider
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory
import org.slf4j.MDC

/**
 * 폴링 워커. [JobQueueProperties.pollInterval] 마다 [JdbcJobRepository.claim] → 핸들러 실행 → DONE / 재시도(백오프) / DEAD.
 *
 * - 폴링은 안전망이다: 같은 프로세스에서 잡을 넣은 트랜잭션이 커밋되면 [wake] 가 바로 깨우고([JdbcJobQueue]), 묶음이 가득 찼으면(밀린 일감이 더 있을 수 있다) 기다리지 않고 바로 다시 집는다
 * - [JobQueueProperties.concurrency] 개의 스레드가 각자 이 고리를 돈다(기본 1). 같은 잡을 둘이 집지 않는 것은 SKIP LOCKED 가 지킨다
 * - 단일 인스턴스면 그냥 돌고, 여러 인스턴스면 SKIP LOCKED 가 분배한다 (Redis 락 없음)
 * - 핸들러 실행은 claim 트랜잭션 **밖** — 긴 작업이 행 락을 오래 잡지 않게
 * - 워커가 죽어 RUNNING 으로 남은 잡은 [JobQueueProperties.staleLockTimeout] 뒤 PENDING 으로 복구 (시도를 다 썼으면 DEAD)
 * - 종료 갱신은 임대 주인(`locked_by` · `attempts`)일 때만 반영하고, 묶음의 각 잡은 차례가 올 때 임대를 새로 잡는다
 * - 돌리는 동안 넣은 쪽의 MDC 문맥을 복원하고, DEAD 가 되는 잡은 [JobDeadListener] 에 알린다
 * - 처리기를 모르는 종류는 집지 않는다 (배포 중 새 버전의 잡을 DEAD 로 만들지 않게) — [JobQueueProperties.unhandledTypeGrace] 가 지나도 남으면 그때 DEAD
 * - 프로젝트 모드면 프로젝트마다 그 스키마의 `jobs` 를 돈다 (프로젝트마다 한 묶음씩, 오류는 프로젝트 안에서 끝낸다)
 */
class JobQueueWorker(
    private val repository: JdbcJobRepository,
    private val handlers: List<JobHandler>,
    private val properties: JobQueueProperties,
    private val timeProvider: TimeProvider,
    private val propagator: JobContextPropagator = MdcJobContextPropagator(properties.propagatedMdcKeys),
    private val deadListeners: List<JobDeadListener> = emptyList(),
    private val retention: JobRetention? = null,
    /** 프로젝트 모드(`skeleton.projects`)면 서비스 중인 프로젝트를 차례로 돈다. null 이면 지금까지처럼 한 서비스 */
    private val projects: ProjectRegistry? = null,
) : JobDrainer {
    private val log = LoggerFactory.getLogger(javaClass)
    private val handlerByType = handlers.associateBy { it.type }
    val workerId: String = properties.workerId.ifBlank { defaultWorkerId() }
    private val running = AtomicBoolean(false)
    private var executor: ExecutorService? = null
    /** 깨우기 표 — [wake] 가 한 장 더하고, 쉬고 있는 스레드 하나가 한 장을 가져가 바로 판을 돈다. 스레드 수보다 쌓이지 않는다 */
    private val wakeups = Semaphore(0)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        if (properties.mode == JobQueueProperties.Mode.ON_REQUEST) {
            // 요청 구동: 스레드를 띄우지 않는다 — 깨우기 주소가 drain 을 부를 때만 처리한다 (인스턴스가 0대로 내려가도 일감은 DB 에 남는다)
            log.info("Job queue worker is on-request (no polling thread): workerId={} batchSize={}", workerId, properties.batchSize)
            return
        }
        val threads = AtomicInteger()
        val pool = Executors.newFixedThreadPool(properties.concurrency) { r ->
            Thread(r, if (properties.concurrency == 1) "skeleton-job-queue" else "skeleton-job-queue-${threads.incrementAndGet()}").apply { isDaemon = true }
        }
        executor = pool
        repeat(properties.concurrency) { pool.execute(::loop) }
        log.info(
            "Job queue worker started: workerId={} pollInterval={} batchSize={} concurrency={}",
            workerId, properties.pollInterval, properties.batchSize, properties.concurrency,
        )
    }

    /** 같은 프로세스의 잡 넣기가 커밋됐다 — 쉬는 스레드가 있으면 기다리던 폴링 간격을 건너뛰고 바로 판을 돌게 한다. 폴링 스레드가 없는 모드에서는 아무 일도 없다 */
    fun wake() {
        if (!running.get() || properties.mode == JobQueueProperties.Mode.ON_REQUEST) return
        if (wakeups.availablePermits() < properties.concurrency) wakeups.release()
    }

    /** 스레드 하나의 고리: 간격 동안(또는 깨우기 전까지) 쉬고, 판을 돌되 묶음이 가득 찼으면 쉬지 않고 이어 돈다 */
    private fun loop() {
        try {
            while (running.get()) {
                wakeups.tryAcquire(properties.pollInterval.toMillis(), TimeUnit.MILLISECONDS)
                do {
                    val handled = safePoll()
                } while (handled >= properties.batchSize && running.get())
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        executor?.shutdownNow()
        executor = null
    }

    /**
     * 밀린 일감을 [budget] 안에서 처리하고 끝낸다 (요청 구동 모드의 깨우기 주소가 부른다). 한 판([pollOnce])을 돌고, 처리한 것이 있고 시간이 남았으면 다음 판 —
     * 처리할 것이 없으면(또는 시간이 다 되면) 멈춘다. 시간 한도는 **새 판을 시작하기 전에만** 본다: 이미 집은 일감은 끝까지 돌리므로 긴 핸들러는 한도를 넘길 수 있다.
     * 아직 이를 때가 안 된 재시도(백오프 대기)는 집지 않는다 — 다음 호출이 그때 집는다.
     * 프로젝트 모드면 판마다 프로젝트를 모두 한 번씩 돈다([pollOnce]).
     */
    override fun drain(budget: java.time.Duration): DrainResult {
        val started = System.nanoTime()
        var rounds = 0
        var processed = 0
        var timedOut = false
        while (true) {
            val handled = pollOnce()
            rounds++
            processed += handled
            if (handled == 0) break
            if (System.nanoTime() - started >= budget.toNanos()) { timedOut = true; break }
        }
        return DrainResult(processed, rounds, timedOut, (System.nanoTime() - started) / 1_000_000)
    }

    /**
     * 이미 넣은 잡 하나(종류 + 내용이 같은 것 중 가장 오래된, 때가 된 것)를 **지금 이 스레드에서** 임대해 돌린다 - 폴링을 기다리지 않는다. 돌렸으면(성공이든 실패·재시도 예약이든) true,
     * 처리기가 없는 종류 · 잡이 없음 · 때가 안 됨 · 남(일꾼 스레드나 다른 인스턴스)이 이미 집음이면 false. 임대 · 종료 갱신 · 백오프 · DEAD 는 폴링으로 돈 잡과 같다.
     * 지금 프로젝트 문맥(프로젝트 모드)의 `jobs` 를 본다 - 부르는 쪽이 문맥 안에서 부른다.
     */
    fun runNow(type: String, payloadJson: String): Boolean {
        if (type !in handlerByType) return false
        val job = repository.claimOne(workerId, type, payloadJson, timeProvider.now()) ?: return false
        run(job)
        return true
    }

    private fun safePoll(): Int =
        try {
            pollOnce(throttleMaintenance = true)
        } catch (ex: Exception) {
            log.error("Job queue poll failed", ex)
            0
        }

    /**
     * 한 번 폴링. 테스트에서 직접 호출할 수 있다. 처리한 잡 수를 돌려준다.
     * 프로젝트 모드면 서비스 중인 프로젝트마다 그 스키마에서 한 묶음([JobQueueProperties.batchSize])씩 — 일감 많은 프로젝트가 다른 프로젝트를 굶기지 않는다.
     * 한 프로젝트의 오류(DB 포함)는 그 프로젝트에서 끝내고 다음 프로젝트로 간다.
     */
    fun pollOnce(): Int = pollOnce(throttleMaintenance = false)

    /**
     * [throttleMaintenance] 면(일꾼 스레드의 고리) 정리 일(스테일 복구 · DEAD 유예 · 보관 정리)은 프로젝트마다 [JobQueueProperties.pollInterval] 에 한 번만 돈다 -
     * 깨우기 · 이어 집기로 판이 잦아져도 잡 하나에 정리 쿼리가 따라붙지 않는다. 판은 `claim` 만 한다.
     */
    private fun pollOnce(throttleMaintenance: Boolean): Int {
        val registry = projects ?: return pollProject(throttleMaintenance)
        var processed = 0
        registry.active.forEach { project ->
            try {
                processed += ProjectContext.runIn(project) { inProject(project) { pollProject(throttleMaintenance) } }
            } catch (ex: Exception) {
                log.error("Job queue poll failed for project '{}'", project.id, ex)
            }
        }
        return processed
    }

    private fun <T> inProject(project: Project, block: () -> T): T {
        MDC.put("project", project.id)
        try {
            return block()
        } finally {
            MDC.remove("project")
        }
    }

    /** 지금 프로젝트 문맥(없으면 한 서비스)의 `jobs` 한 번 폴링 */
    private fun pollProject(throttleMaintenance: Boolean): Int {
        val now = timeProvider.now()
        if (!throttleMaintenance || maintenanceDue()) maintain(now)
        return claimAndRun(now)
    }

    private val lastMaintenance = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 이 프로젝트(없으면 한 서비스)의 정리를 지난 [JobQueueProperties.pollInterval] 안에 돌았나 - 아니면 지금 도는 것으로 표시한다(스레드 여럿 중 하나만 통과). 실제 시계로 잰다 */
    private fun maintenanceDue(): Boolean {
        val key = ProjectContext.currentOrNull()?.id.orEmpty()
        val nowNanos = System.nanoTime()
        var due = false
        lastMaintenance.compute(key) { _, last ->
            if (last == null || nowNanos - last >= properties.pollInterval.toNanos()) { due = true; nowNanos } else last
        }
        return due
    }

    private fun maintain(now: java.time.Instant) {
        val stale = repository.recoverStale(now.minus(properties.staleLockTimeout), now)
        if (stale.recovered > 0) log.warn("Recovered {} stale RUNNING job(s)", stale.recovered)
        stale.dead.forEach { job ->
            // 워커가 죽은 채 시도를 다 쓴 잡 — 복구를 돌리는 이 워커가 대신 알린다 (독 작업이 조용히 DEAD 로 묻히지 않게)
            log.warn("Job {} ({}) was stale RUNNING with all {} attempts used, marked DEAD", job.id, job.type, job.maxAttempts)
            notifyDead(job, job.lastError ?: "stale RUNNING job exhausted its attempts")
        }
        deadenUnhandled(now)
        runRetention()
    }

    private fun claimAndRun(now: java.time.Instant): Int {
        // 아는 종류만 집는다 — 모르는 종류(배포 중 새 버전이 넣은 잡)는 시도도 쓰지 않고 남아, 그 종류를 아는 워커가 집는다
        val jobs = repository.claim(workerId, properties.batchSize, now, handlerByType.keys)
        jobs.forEach { job ->
            // 차례가 온 잡의 임대를 새로 잡는다 — 못 잡으면 앞 잡이 오래 걸려 그 사이 복구되어 남에게 넘어갔다. 돌리지 않는다
            if (repository.renew(job.id, workerId, job.attempts, timeProvider.now())) run(job)
            else log.warn("Job {} ({}) was reclaimed by another worker before its turn, skipped", job.id, job.type)
        }
        return jobs.size
    }

    /** 이 워커가 모르는 종류가 [JobQueueProperties.unhandledTypeGrace] 가 지나도록 남아 있으면 처리기가 어디에도 없다는 뜻이다 — 그때 DEAD */
    private fun deadenUnhandled(now: java.time.Instant) {
        repository.deadenUnhandled(handlerByType.keys, now.minus(properties.unhandledTypeGrace), now) { type ->
            "No handler registered for job type '$type' (waited ${properties.unhandledTypeGrace} for a worker that has one)"
        }.forEach { job ->
            log.error("No worker has a handler for job type '{}' (job {}), marked DEAD after {}", job.type, job.id, properties.unhandledTypeGrace)
            notifyDead(job, "No handler registered for job type '${job.type}'")
        }
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
        // claim 이 아는 종류만 집으므로 처리기는 항상 있다
        val handler = handlerByType.getValue(job.type)
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
