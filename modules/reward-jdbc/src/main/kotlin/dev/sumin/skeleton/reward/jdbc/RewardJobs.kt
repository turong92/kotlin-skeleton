package dev.sumin.skeleton.reward.jdbc

import dev.sumin.skeleton.jobqueue.jdbc.Job
import dev.sumin.skeleton.jobqueue.jdbc.JobDeadListener
import dev.sumin.skeleton.jobqueue.jdbc.JobHandler
import dev.sumin.skeleton.jobqueue.jdbc.JobQueue
import dev.sumin.skeleton.jobqueue.jdbc.PermanentJobFailureException
import dev.sumin.skeleton.common.reward.RewardPermanentFailure
import dev.sumin.skeleton.reward.RewardGrantStore
import dev.sumin.skeleton.reward.RewardTasks
import dev.sumin.skeleton.reward.RewardWorker
import org.slf4j.LoggerFactory

const val REWARD_JOB_TYPE = "reward.issue"

/** 작업 줄의 내용 — `{"grantId":"…"}` 하나. 상태를 싣지 않는다(처리기는 지급 건 줄을 다시 읽어 정한다). */
private val GRANT_ID = Regex("[A-Za-z0-9_.:-]{1,40}")
private val PAYLOAD = Regex("""\{"grantId":"([A-Za-z0-9_.:-]{1,40})"}""")

/** [RewardTasks] 를 `job-queue-jdbc` 에 싣는다 — **호출자 트랜잭션 안에서만**: 지급 건 줄이 커밋되면 작업도 있고 되돌아가면 함께 사라진다. */
class JdbcRewardTasks(
    private val queue: JobQueue,
    private val store: RewardGrantStore,
    private val maxAttempts: Int?,
) : RewardTasks {
    override fun enqueue(grantId: String) {
        require(GRANT_ID.matches(grantId)) { "a reward grant id must be a plain identifier" }
        check(store.hasOpenTransaction()) { "RewardTasks.enqueue must be called inside a transaction: the job must roll back with the grant row" }
        queue.enqueue(REWARD_JOB_TYPE, """{"grantId":"$grantId"}""", null, maxAttempts)
    }
}

/**
 * `reward.issue` 작업 하나 = 지급 건 하나. 일꾼이 던지면 큐의 재시도 · 백오프에 맡기고, 영구 실패는 재시도 없이 바로 DEAD 로 보낸다.
 * 큐 워커는 처리기를 트랜잭션으로 감싸지 않는다 - 일꾼이 건을 잠그는 트랜잭션을 직접 연다. [worker] 는 호출 때마다 읽는다(빈 순환을 피한다).
 */
class RewardJobHandler(private val worker: () -> RewardWorker) : JobHandler {
    override val type: String = REWARD_JOB_TYPE

    override fun handle(job: Job) {
        val grantId = PAYLOAD.matchEntire(job.payloadJson)?.groupValues?.get(1)
            ?: throw PermanentJobFailureException("reward job ${job.id} has an unreadable payload")
        try {
            worker().process(grantId)
        } catch (e: RewardPermanentFailure) {
            throw PermanentJobFailureException(e.message ?: "permanent reward failure", e)
        }
    }
}

/**
 * 작업이 DEAD 가 되는 모든 길(재시도를 다 씀 · 영구 실패 · 처리기 없음 · 멈춘 채 시도를 다 씀)에서 부르는 고리 - 우리 작업이면 지급 건을 `FAILED` 로 적는다
 * (이미 `PENDING` 이 아니면 아무것도 하지 않는다). 운영자는 「다시 지급」으로 건을 되살린다. 던지지 않는다.
 */
class RewardDeadListener(private val store: () -> RewardGrantStore) : JobDeadListener {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun onDead(job: Job, reason: String) {
        if (job.type != REWARD_JOB_TYPE) return
        val grantId = PAYLOAD.matchEntire(job.payloadJson)?.groupValues?.get(1) ?: return
        try {
            store().markFailed(grantId, reason)
        } catch (e: Exception) {
            log.warn("reward dead-job hook failed job={} exception={}", job.id, e.javaClass.simpleName)
        }
    }
}
