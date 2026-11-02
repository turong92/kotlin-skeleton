package dev.sumin.skeleton.notification.push.jdbc

import dev.sumin.skeleton.jobqueue.jdbc.Job
import dev.sumin.skeleton.jobqueue.jdbc.JobDeadListener
import dev.sumin.skeleton.jobqueue.jdbc.JobHandler
import dev.sumin.skeleton.jobqueue.jdbc.JobQueue
import dev.sumin.skeleton.jobqueue.jdbc.PermanentJobFailureException
import dev.sumin.skeleton.json.JsonCodec
import dev.sumin.skeleton.notification.DeliveryOutcome
import dev.sumin.skeleton.notification.NotificationDeliveryHooks
import dev.sumin.skeleton.notification.push.PushChannel
import dev.sumin.skeleton.notification.push.PushDeliveryWorker
import dev.sumin.skeleton.notification.push.PushJob
import dev.sumin.skeleton.notification.push.PushPermanentFailure
import dev.sumin.skeleton.notification.push.PushTasks
import java.time.Instant

const val PUSH_JOB_TYPE = "notification.push"

/** [PushTasks] 를 `job-queue-jdbc` 에 싣는다 - 작업 하나 = 기기 하나에 푸시 하나. 재시도가 다른 기기에 중복을 만들지 않는다 */
class JdbcPushTasks(
    private val queue: JobQueue,
    private val json: JsonCodec,
    private val maxAttempts: Int?,
) : PushTasks {
    override fun enqueue(job: PushJob, runAt: Instant?) {
        queue.enqueue(PUSH_JOB_TYPE, json.stringify(json.toDocument(job)), runAt, maxAttempts)
    }
}

/**
 * `notification.push` 작업 하나를 [PushDeliveryWorker] 에 넘긴다. 일꾼이 던지는 재시도([dev.sumin.skeleton.notification.push.PushRetryException])는 큐의 백오프에 맡기고,
 * 영구 실패는 재시도 없이 바로 DEAD 로 보낸다. [worker] 는 호출 때마다 읽는다(빈 순환을 피한다).
 */
class PushJobHandler(private val json: JsonCodec, private val worker: () -> PushDeliveryWorker) : JobHandler {
    override val type: String = PUSH_JOB_TYPE

    override fun handle(job: Job) {
        val push = try {
            json.fromDocument(json.parse(job.payloadJson), PushJob::class)
        } catch (e: Exception) {
            throw PermanentJobFailureException("push job ${job.id} has an unreadable payload", e)
        }
        try {
            worker().process(push)
        } catch (e: PushPermanentFailure) {
            throw PermanentJobFailureException(e.message ?: "permanent push failure", e)
        }
    }
}

/**
 * 작업이 DEAD 가 되는 모든 길(재시도를 다 씀 · 영구 실패 · 처리기 없음 · 멈춘 채 시도를 다 씀)에서 부르는 고리 - 우리 푸시 작업이면 전달 고리에 FAILED 로 알린다.
 * 내용을 못 읽으면 어느 이벤트인지 몰라 알리지 않는다. 던지지 않는다.
 */
class PushDeadListener(private val json: JsonCodec, private val hooks: () -> NotificationDeliveryHooks) : JobDeadListener {
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    override fun onDead(job: Job, reason: String) {
        if (job.type != PUSH_JOB_TYPE) return
        try {
            val push = json.fromDocument(json.parse(job.payloadJson), PushJob::class)
            hooks().onOutcome(push.message.eventId, PushChannel.KEY, DeliveryOutcome.FAILED)
        } catch (e: Exception) {
            log.warn("push dead-job hook failed job={} exception={}", job.id, e.javaClass.simpleName)
        }
    }
}
