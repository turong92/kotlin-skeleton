package dev.sumin.skeleton.jobqueue.jdbc

import java.time.Instant

data class JobRetentionResult(val done: Int, val dead: Int)

/**
 * 끝난 줄 정리 — `skeleton.job-queue.retention.enabled=true` 일 때 워커 폴링이 [JobQueueProperties.Retention.interval] 마다 부른다.
 * 줄은 쌓이기만 해서 조회가 점점 느려진다. DONE 은 [JobQueueProperties.Retention.done], DEAD 는 [JobQueueProperties.Retention.dead]
 * 가 지난 것만 지운다. PENDING · RUNNING 은 건드리지 않는다. 멱등.
 */
class JobRetention(
    private val repository: JdbcJobRepository,
    private val config: JobQueueProperties.Retention,
    private val timeProvider: dev.sumin.skeleton.common.time.TimeProvider,
) {
    /** 프로젝트마다 따로 — 한 프로젝트의 정리가 다른 프로젝트의 정리를 가리지 않게. 프로젝트 문맥이 없으면(한 서비스) 키는 하나 */
    private val lastRun = java.util.concurrent.ConcurrentHashMap<String, Instant>()

    fun run(now: Instant): JobRetentionResult {
        val batch = config.batchSize.coerceAtLeast(1)
        return JobRetentionResult(
            done = repository.deleteFinishedBefore(JobStatus.DONE, now.minus(config.done), batch),
            dead = repository.deleteFinishedBefore(JobStatus.DEAD, now.minus(config.dead), batch),
        )
    }

    /** 간격이 지났을 때만 돈다. 돌았으면 결과, 아니면 null */
    fun runIfDue(): JobRetentionResult? {
        val now = timeProvider.now()
        val key = dev.sumin.skeleton.common.project.ProjectContext.currentOrNull()?.id.orEmpty()
        val last = lastRun[key]
        if (last != null && now.isBefore(last.plus(config.interval))) return null
        lastRun[key] = now
        return run(now)
    }
}
