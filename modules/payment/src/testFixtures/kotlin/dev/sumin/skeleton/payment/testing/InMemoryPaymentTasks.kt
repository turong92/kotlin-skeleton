package dev.sumin.skeleton.payment.testing

import dev.sumin.skeleton.payment.PaymentTask
import dev.sumin.skeleton.payment.PaymentTaskPermanentException
import dev.sumin.skeleton.payment.PaymentTaskRun
import dev.sumin.skeleton.payment.PaymentTaskRunner
import dev.sumin.skeleton.payment.PaymentTasks
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 시험용 메모리 [PaymentTasks] + 아주 작은 작업 실행기.
 * - [enqueue] 는 **[InMemoryPaymentLedgerStore] 의 트랜잭션 안에서만** 된다(밖이면 던진다 — 진짜 `JobQueue` 는 밖에서도 혼자 커밋해 버려서 놓치기 쉬운 실수를 일부러 더 엄격하게 잡는다).
 *   커밋되면 보이고 롤백되면 사라진다.
 * - [runDue] 는 큐 워커의 의미를 흉내 낸다: 시도마다 `attempt` 가 1 늘고, [PaymentTaskPermanentException] 은 바로 DEAD, 다른 예외는 시도를 다 쓰면 DEAD 아니면 다음 [runDue] 에서 다시.
 *   DEAD 가 되는 순간 큐처럼 [PaymentTaskRunner.onDead] 를 부른다(죽은 작업의 고리 — 던져도 삼킨다).
 *   백오프 시간은 흉내 내지 않는다(그건 `job-queue-jdbc` 의 시험이 잰다).
 */
class InMemoryPaymentTasks(
    private val store: InMemoryPaymentLedgerStore,
    private val defaultMaxAttempts: Int = 5,
) : PaymentTasks {
    enum class State { PENDING, RUNNING, DONE, DEAD }

    class Record(val task: PaymentTask, val maxAttempts: Int) {
        @Volatile var attempts: Int = 0
        @Volatile var state: State = State.PENDING
        @Volatile var lastError: String? = null
    }

    private val records = CopyOnWriteArrayList<Record>()

    override fun enqueue(task: PaymentTask) {
        val tx = checkNotNull(store.currentTransaction()) { "PaymentTasks.enqueue must be called inside PaymentLedgerStore.inTransaction" }
        tx.afterCommit { records += Record(task, task.maxAttempts ?: defaultMaxAttempts) }
    }

    /** 커밋된 작업 전부(끝난 것 포함). */
    fun all(): List<Record> = records.toList()

    fun all(type: String): List<Record> = records.filter { it.task.type == type }

    fun pending(type: String? = null): List<Record> =
        records.filter { it.state == State.PENDING && (type == null || it.task.type == type) }

    fun dead(type: String? = null): List<Record> =
        records.filter { it.state == State.DEAD && (type == null || it.task.type == type) }

    /**
     * 시간이 된([now] 이하 `runAt`) 대기 작업을 한 번씩 돈다. [only] 로 종류를 좁힌다. 돈 작업 수를 돌려준다.
     * 처리기가 새 작업을 넣어도 이번 호출에서는 돌지 않는다.
     */
    fun runDue(runner: PaymentTaskRunner, now: Instant, only: Set<String>? = null): Int {
        val due = records.filter { it.state == State.PENDING && (only == null || it.task.type in only) && (it.task.runAt == null || !it.task.runAt.isAfter(now)) }
        return due.count { record -> claim(record) && run { attempt(record, runner); true } }
    }

    /**
     * 종류 · 대상이 맞는, 시간이 된 대기 작업 하나를 **임대해서** 지금 돈다(`job-queue-jdbc` 의 `runNow` 흉내) - 이미 누가 집었으면(RUNNING) false.
     * 느린 처리기가 도는 동안 [runDue] 가 같은 작업을 다시 집지 않는다.
     */
    fun runNow(runner: PaymentTaskRunner, now: Instant, type: String, subjectId: String): Boolean {
        val record = records.firstOrNull {
            it.state == State.PENDING && it.task.type == type && it.task.subjectId == subjectId && (it.task.runAt == null || !it.task.runAt.isAfter(now))
        } ?: return false
        if (!claim(record)) return false
        attempt(record, runner)
        return true
    }

    @Synchronized
    private fun claim(record: Record): Boolean {
        if (record.state != State.PENDING) return false
        record.state = State.RUNNING
        return true
    }

    private fun attempt(record: Record, runner: PaymentTaskRunner) {
        record.attempts += 1
        try {
            runner.run(PaymentTaskRun(record.task.type, record.task.subjectId, record.attempts, record.maxAttempts))
            record.state = State.DONE
        } catch (e: PaymentTaskPermanentException) {
            record.lastError = "${e::class.java.name}: ${e.message}"
            die(record, runner)
        } catch (e: Exception) {
            record.lastError = "${e::class.java.name}: ${e.message}"
            if (record.attempts >= record.maxAttempts) die(record, runner) else record.state = State.PENDING
        }
    }

    private fun die(record: Record, runner: PaymentTaskRunner) {
        record.state = State.DEAD
        // 큐의 JobDeadListener 처럼 — 듣는 쪽이 던져도 큐는 계속 돈다
        runCatching { runner.onDead(PaymentTaskRun(record.task.type, record.task.subjectId, record.attempts, record.maxAttempts)) }
    }
}
