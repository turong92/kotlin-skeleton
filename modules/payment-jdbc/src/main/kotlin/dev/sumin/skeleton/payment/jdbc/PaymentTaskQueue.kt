package dev.sumin.skeleton.payment.jdbc

import dev.sumin.skeleton.jobqueue.jdbc.Job
import dev.sumin.skeleton.jobqueue.jdbc.JobDeadListener
import dev.sumin.skeleton.jobqueue.jdbc.JobHandler
import dev.sumin.skeleton.jobqueue.jdbc.JobQueue
import dev.sumin.skeleton.jobqueue.jdbc.JobQueueWorker
import dev.sumin.skeleton.jobqueue.jdbc.PermanentJobFailureException
import dev.sumin.skeleton.payment.PaymentLedgerStore
import dev.sumin.skeleton.payment.PaymentTask
import dev.sumin.skeleton.payment.PaymentTaskPermanentException
import dev.sumin.skeleton.payment.PaymentTaskRun
import dev.sumin.skeleton.payment.PaymentTaskRunner
import dev.sumin.skeleton.payment.PaymentTasks
import dev.sumin.skeleton.payment.PaymentTasksNow
import org.slf4j.LoggerFactory

/** 작업 줄의 내용 — `{"subjectId":"…"}` 하나. 작업에 상태를 싣지 않는다(처리기는 그 줄을 다시 읽어 정한다). */
private val SUBJECT_ID = Regex("[A-Za-z0-9_.:-]{1,200}")
private val PAYLOAD = Regex("""\{"subjectId":"([A-Za-z0-9_.:-]{1,200})"}""")

/**
 * [PaymentTasks] 를 `job-queue-jdbc` 에 싣는다(스펙 §4.4) — 자체 폴링 워커 · 임대 · 백오프 열을 만들지 않는다.
 *
 * **장부 트랜잭션 안에서만 넣는다**: [JobQueue.enqueue] 는 부르는 쪽의 트랜잭션(같은 `DataSource` 의 같은 연결)에 참여하므로 장부 줄이 커밋되면 작업도 있고
 * 롤백되면 함께 사라진다. 트랜잭션 밖에서 부르면 큐가 혼자 커밋해 버려 "줄은 없는데 작업은 있다"가 되므로 던진다.
 */
class JdbcPaymentTasks(
    private val queue: JobQueue,
    private val store: PaymentLedgerStore,
) : PaymentTasks {
    override fun enqueue(task: PaymentTask) {
        require(SUBJECT_ID.matches(task.subjectId)) { "a payment task subject must be a plain identifier (letters, digits, _ . : -), up to 200 characters" }
        check(store.hasOpenTransaction()) { "PaymentTasks.enqueue must be called inside a ledger transaction (PaymentLedgerStore.inTransaction): the job must roll back with the ledger row" }
        queue.enqueue(task.type, payloadOf(task.subjectId), task.runAt, task.maxAttempts)
    }
}

private fun payloadOf(subjectId: String) = """{"subjectId":"$subjectId"}"""

/**
 * [PaymentTasksNow] - 방금 넣은 작업을 큐 워커의 임대로 지금 돌린다(`JobQueueWorker.runNow`: 같은 처리기 · 같은 종료 갱신 · 같은 백오프). 워커가 없는 프로세스
 * (`skeleton.job-queue.worker-enabled=false`)에서는 아무것도 돌리지 않는다 - 작업은 다른 인스턴스의 일꾼이 맡는다. [worker] 는 호출 때마다 읽는다(빈 순환을 피한다).
 */
class JdbcPaymentTasksNow(private val worker: () -> JobQueueWorker?) : PaymentTasksNow {
    override fun runNow(type: String, subjectId: String): Boolean {
        require(SUBJECT_ID.matches(subjectId)) { "a payment task subject must be a plain identifier" }
        return worker()?.runNow(type, payloadOf(subjectId)) ?: false
    }
}

/**
 * 작업 종류 하나의 큐 처리기 — 줄에서 대상 id 를 꺼내 [PaymentTaskRunner.run] 을 부른다.
 * - [PaymentTaskPermanentException](다시 해 봐야 소용없다) → [PermanentJobFailureException]: 재시도 없이 바로 DEAD(경보는 `alert` 가 `JOB_DEAD` 로 낸다)
 * - 그 밖의 예외([dev.sumin.skeleton.payment.PaymentTaskRetryException] 포함)는 그대로 던져 큐의 재시도 · 백오프에 맡긴다
 * 큐 워커는 처리기를 트랜잭션으로 감싸지 않는다 — 결제사를 부르는 처리기가 락 · 트랜잭션 밖에서 돌아야 하기 때문이다. 여기서도 감싸지 않는다.
 */
class PaymentJobHandler(
    override val type: String,
    private val runner: PaymentTaskRunner,
) : JobHandler {
    override fun handle(job: Job) {
        val subjectId = PAYLOAD.matchEntire(job.payloadJson)?.groupValues?.get(1)
            ?: throw PermanentJobFailureException("payment job ${job.id} has an unreadable payload")   // 내용은 싣지 않는다
        try {
            runner.run(PaymentTaskRun(type, subjectId, job.attempts, job.maxAttempts))
        } catch (e: PaymentTaskPermanentException) {
            throw PermanentJobFailureException(e.message ?: "permanent payment task failure", e)
        }
    }
}

/**
 * 큐가 작업을 **DEAD 로 만드는 모든 길**(재시도를 다 씀 · 영구 실패 · 처리기 없음 · **워커가 죽어 멈춘 채 시도를 다 씀**)에서 부르는 고리(`job-queue-jdbc` 의 [JobDeadListener]) —
 * 우리 작업이면 대상 id 를 꺼내 [PaymentTaskRunner.onDead] 로 넘기고, 종류의 처리기가 줄에 "사람이 봐야 한다"를 찍는다(조회 작업 → `CONFIRM_UNRESOLVED`, 환불 정산 → `REFUND_MISMATCH`).
 * 처리기 안의 마지막 시도 처리는 멈춤 복구(처리기를 거치지 않는다)나 마지막 시도에서 DB 오류로 던진 경우에 닿지 못하므로 이 고리가 메운다.
 *
 * **불리는 곳**: 큐 워커 스레드, 트랜잭션 밖(DEAD 로 적는 문장이 끝난 뒤). 워커는 듣는 쪽이 던져도 로그만 남기고 계속 돈다 — 여기서도 던지지 않고 로그를 남긴다(종류 · 작업 id · 예외 종류만; 주문 id · 메시지는 싣지 않는다).
 * 다른 모듈의 작업(`alert` 등이 같은 큐를 쓴다)은 건드리지 않는다 — 러너가 아는 종류이고 내용이 우리 모양일 때만. 내용을 못 읽는 작업은 [PaymentJobHandler] 가 영구 실패로 죽인 것이라 표시할 줄이 없다.
 * [runner] 는 호출 때마다 읽는다(워커가 만들어질 때 러너가 아직 없어도 된다 — 빈 순환을 피한다).
 */
class PaymentJobDeadListener(private val runner: () -> PaymentTaskRunner?) : JobDeadListener {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun onDead(job: Job, reason: String) {
        val runner = runner() ?: return
        if (job.type !in runner.types) return
        val subjectId = PAYLOAD.matchEntire(job.payloadJson)?.groupValues?.get(1) ?: return
        try {
            runner.onDead(PaymentTaskRun(job.type, subjectId, job.attempts, job.maxAttempts))
        } catch (e: Exception) {
            log.warn("payment dead-job hook failed type={} job={} exception={}", job.type, job.id, e.javaClass.simpleName)
        }
    }
}
