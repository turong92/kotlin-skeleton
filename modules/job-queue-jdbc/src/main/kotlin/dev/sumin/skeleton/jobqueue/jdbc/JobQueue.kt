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
    /** 넣을 때의 로그 문맥(traceId 등) — [JobContextPropagator] 가 만든 문자열. 옛 줄 · 문맥 없이 넣은 줄은 null */
    val logContext: String? = null,
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

/**
 * 잡이 DEAD 가 되는 순간(재시도를 다 썼다 · 영구 실패 · 처리기 없음 · 멈춘 채 시도를 다 썼다)을 밖에 알리는 고리.
 * 빈으로 등록하면 [JobQueueWorker] 가 부른다. [reason] 은 예외 종류 · 메시지 한 줄 — 어디로 내보낼지, 비밀을 가리는 것은 듣는 쪽 몫이다.
 * 듣는 쪽이 던져도 워커는 로그만 남기고 계속 돈다. 임대를 잃은 워커의 DEAD 시도(남이 이미 가져간 잡)는 알리지 않는다.
 * 스테일 복구로 DEAD 가 된 잡은 복구를 돌리는 살아 있는 워커가 대신 알린다.
 */
fun interface JobDeadListener {
    fun onDead(job: Job, reason: String)
}
