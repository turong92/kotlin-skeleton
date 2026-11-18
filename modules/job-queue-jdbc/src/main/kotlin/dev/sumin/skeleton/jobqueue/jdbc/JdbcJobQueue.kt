package dev.sumin.skeleton.jobqueue.jdbc

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.wake.JobWake
import dev.sumin.skeleton.common.wake.requestAfterCommit
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

class JdbcJobQueue(
    private val repository: JdbcJobRepository,
    private val properties: JobQueueProperties,
    private val timeProvider: TimeProvider,
    private val propagator: JobContextPropagator = MdcJobContextPropagator(properties.propagatedMdcKeys),
    /** 넣은 잡이 **커밋된 뒤**(트랜잭션 밖이면 바로) 부른다 — 같은 프로세스의 일꾼을 깨운다. 실패는 삼킨다(폴링이 안전망) */
    private val onCommitted: () -> Unit = {},
    private val lanes: JobLanes = JobLanes.NONE,
    /** 있으면 넣은 잡이 **커밋된 뒤**(트랜잭션 밖이면 바로) 그 실행 시각에 깨우기가 불리게 예약한다 — 롤백된 잡은 예약하지 않는다. 인스턴스가 0대여도 일감이 처리된다 */
    private val jobWake: JobWake? = null,
) : JobQueue {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun enqueue(type: String, payloadJson: String, runAt: Instant?, maxAttempts: Int?): Long {
        require(type.isNotBlank()) { "Job type must not be blank." }
        val now = timeProvider.now()
        val id = repository.insert(type, payloadJson, runAt ?: now, maxAttempts ?: properties.maxAttempts, now, propagator.capture(), lanes.enqueueLane())
        afterCommit()
        jobWake?.requestAfterCommit(runAt ?: now)
        return id
    }

    private fun afterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() = signal()
            })
        } else {
            signal()
        }
    }

    private fun signal() {
        try {
            onCommitted()
        } catch (ex: Exception) {
            log.warn("Waking the job worker failed: {}", ex.javaClass.simpleName)
        }
    }
}
