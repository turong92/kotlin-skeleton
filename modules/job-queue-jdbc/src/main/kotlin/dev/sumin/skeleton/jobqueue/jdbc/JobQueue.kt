package dev.sumin.skeleton.jobqueue.jdbc

import java.time.Instant

enum class JobStatus { PENDING, RUNNING, DONE, DEAD }

data class Job(
    val id: Long,
    val type: String,
    val payloadJson: String,
    val status: JobStatus,
    val attempts: Int,
    val maxAttempts: Int,
    val nextRunAt: Instant,
    val lockedBy: String?,
    val lockedAt: Instant?,
    val lastError: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** 큐에 넣는 쪽. 같은 트랜잭션 안에서 도메인 변경과 함께 enqueue 하면 "저장됐으면 반드시 실행" 이 보장된다. */
interface JobQueue {
    fun enqueue(type: String, payloadJson: String, runAt: Instant? = null, maxAttempts: Int? = null): Long
}

/**
 * 잡 종류별 처리기. 빈으로 등록하면 [JobQueueWorker] 가 [type] 으로 찾는다.
 * 예외를 던지면 실패로 기록되고 백오프 후 재시도, `maxAttempts` 를 넘기면 DEAD.
 * 재시도되므로 **멱등** 하게 작성한다.
 */
interface JobHandler {
    val type: String
    fun handle(job: Job)
}

/** 영구 실패로 즉시 DEAD 처리하고 싶을 때 던진다 (재시도 안 함) */
class PermanentJobFailureException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
