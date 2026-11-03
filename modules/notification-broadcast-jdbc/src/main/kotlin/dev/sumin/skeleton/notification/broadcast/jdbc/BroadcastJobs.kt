package dev.sumin.skeleton.notification.broadcast.jdbc

import dev.sumin.skeleton.jobqueue.jdbc.Job
import dev.sumin.skeleton.jobqueue.jdbc.JobDeadListener
import dev.sumin.skeleton.jobqueue.jdbc.JobHandler
import dev.sumin.skeleton.jobqueue.jdbc.JobQueue
import dev.sumin.skeleton.jobqueue.jdbc.PermanentJobFailureException
import dev.sumin.skeleton.json.JsonCodec
import dev.sumin.skeleton.notification.broadcast.BroadcastPermanentFailure
import dev.sumin.skeleton.notification.broadcast.BroadcastStep
import dev.sumin.skeleton.notification.broadcast.BroadcastStepRunner
import dev.sumin.skeleton.notification.broadcast.BroadcastTasks
import java.time.Instant

const val BROADCAST_STEP_JOB_TYPE = "notification.broadcast.step"

/** [BroadcastTasks] 를 `job-queue-jdbc` 에 싣는다 - 작업 하나 = 대상 한 묶음. 넣기는 호출자 트랜잭션에 묶인다(`runAt` = 예약 시각 · 묶음 사이 간격) */
class JdbcBroadcastTasks(
    private val queue: JobQueue,
    private val json: JsonCodec,
    private val maxAttempts: Int,
) : BroadcastTasks {
    override fun enqueue(step: BroadcastStep, runAt: Instant?) {
        queue.enqueue(BROADCAST_STEP_JOB_TYPE, json.stringify(json.toDocument(step)), runAt, maxAttempts)
    }
}

/** `notification.broadcast.step` 작업 하나를 [BroadcastStepRunner] 에 넘긴다(처리기는 트랜잭션 밖 - 러너가 스스로 연다). 영구 실패는 재시도 없이 DEAD. [runner] 는 호출 때마다 읽는다(빈 순환을 피한다) */
class BroadcastStepJobHandler(private val json: JsonCodec, private val runner: () -> BroadcastStepRunner) : JobHandler {
    override val type: String = BROADCAST_STEP_JOB_TYPE

    override fun handle(job: Job) {
        val step = try {
            json.fromDocument(json.parse(job.payloadJson), BroadcastStep::class)
        } catch (e: Exception) {
            throw PermanentJobFailureException("broadcast job ${job.id} has an unreadable payload", e)
        }
        try {
            runner().run(step)
        } catch (e: BroadcastPermanentFailure) {
            throw PermanentJobFailureException(e.message ?: "permanent broadcast failure", e)
        }
    }
}

/** 단계 작업이 DEAD 가 되는 모든 길에서 부른다 - 우리 작업이면 공지를 FAILED 로(운영자가 「이어 보내기」). 내용을 못 읽으면 어느 공지인지 몰라 아무것도 안 한다. 던지지 않는다 */
class BroadcastDeadListener(private val json: JsonCodec, private val runner: () -> BroadcastStepRunner) : JobDeadListener {
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    override fun onDead(job: Job, reason: String) {
        if (job.type != BROADCAST_STEP_JOB_TYPE) return
        try {
            runner().fail(json.fromDocument(json.parse(job.payloadJson), BroadcastStep::class), reason)
        } catch (e: Exception) {
            log.warn("broadcast dead-job hook failed job={} exception={}", job.id, e.javaClass.simpleName)
        }
    }
}
