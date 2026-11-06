package dev.sumin.skeleton.point.jdbc

import dev.sumin.skeleton.jobqueue.jdbc.Job
import dev.sumin.skeleton.jobqueue.jdbc.JobDeadListener
import dev.sumin.skeleton.jobqueue.jdbc.JobHandler
import dev.sumin.skeleton.jobqueue.jdbc.JobQueue
import dev.sumin.skeleton.jobqueue.jdbc.PermanentJobFailureException
import dev.sumin.skeleton.point.PointOperationScheduler
import dev.sumin.skeleton.point.PointOperationStatus
import dev.sumin.skeleton.point.PointOperations
import java.time.Instant
import org.slf4j.LoggerFactory

const val POINT_OPERATION_JOB_TYPE = "point.op.resolve"

private val PAYLOAD = Regex("""\{"operationId":"(op_[A-Za-z0-9]{1,40})"}""")

private fun operationIdOf(job: Job): String? = PAYLOAD.matchEntire(job.payloadJson)?.groupValues?.get(1)

/** 불명 건 조회를 `job-queue-jdbc` 에 싣는다 - 작업 줄 내용은 작업 id 하나뿐이다(상태 · 금액 · 키를 싣지 않는다). 영수증과 같은 트랜잭션에서 불린다 */
class JobQueuePointOperationScheduler(private val queue: JobQueue, private val maxAttempts: Int) : PointOperationScheduler {
    override fun schedule(operationId: String, runAt: Instant) {
        queue.enqueue(POINT_OPERATION_JOB_TYPE, """{"operationId":"$operationId"}""", runAt, maxAttempts)
    }
}

/**
 * `point.op.resolve` 작업 하나 = 작업 하나의 조회. 원장으로 가르고(쓰지 않는다), 끝났으면(성공 · 확정 실패 · 모르는 작업) 작업을 마친다.
 * 아직 못 찾았으면 던져서 큐의 재시도 · 백오프에 맡긴다 - 시도를 다 쓰면 DEAD 가 되고 [PointOperationDeadListener] 가 작업을 포기로 닫는다. [operations] 는 호출 때마다 읽는다(빈 순환을 피한다).
 */
class PointOperationResolveHandler(private val operations: () -> PointOperations) : JobHandler {
    override val type: String = POINT_OPERATION_JOB_TYPE

    override fun handle(job: Job) {
        val id = operationIdOf(job) ?: throw PermanentJobFailureException("point operation job ${job.id} has an unreadable payload")
        val op = operations().resolve(id) ?: return
        check(op.status == PointOperationStatus.SUCCEEDED || op.status == PointOperationStatus.FAILED) { "point operation not settled yet; retry" }
    }
}

/** 조회 작업이 DEAD 가 되면(시도를 다 썼다) 작업을 포기(ABANDONED)로 닫는다. 이후 원장 줄이 나타나면 [PointOperations.resolve] 가 성공으로 바로잡는다. 던지지 않는다 */
class PointOperationDeadListener(private val operations: () -> PointOperations) : JobDeadListener {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun onDead(job: Job, reason: String) {
        if (job.type != POINT_OPERATION_JOB_TYPE) return
        val id = operationIdOf(job) ?: return
        try {
            operations().abandon(id)
        } catch (e: Exception) {
            log.warn("point operation dead-job hook failed job={} exception={}", job.id, e.javaClass.simpleName)
        }
    }
}
