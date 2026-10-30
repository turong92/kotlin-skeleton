package dev.sumin.skeleton.search.jdbc

import dev.sumin.skeleton.common.search.SearchSource
import dev.sumin.skeleton.jobqueue.jdbc.Job
import dev.sumin.skeleton.jobqueue.jdbc.JobHandler
import dev.sumin.skeleton.jobqueue.jdbc.JobQueue
import dev.sumin.skeleton.jobqueue.jdbc.PermanentJobFailureException
import dev.sumin.skeleton.search.SearchEngine
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * `search-rebuild` 작업 하나 = 소스의 한 쪽(500개). 쪽을 색인하고 다음 쪽을 큐에 다시 넣는 것까지 **한 트랜잭션**이다(커밋되면 둘 다 있고 되돌아가면 둘 다 없다).
 * 마지막 쪽에서는 그 종류에서 시작 시각 전에 색인된 줄(= 이번에 소스가 내놓지 않은, 사라진 원본)을 지우고 끝을 적는다. 멱등 - 재시도하면 같은 쪽을 다시 쓴다.
 * 다른 실행이 넘겨받았거나 끝난 뒤 뒤늦게 도는 옛 작업 줄은 아무것도 하지 않고 끝난다. [sources] 는 부를 때마다 읽는다.
 */
class SearchRebuildJobHandler(
    private val engine: SearchEngine,
    private val rebuilds: JdbcSearchRebuilds,
    private val queue: JobQueue,
    transactionManager: PlatformTransactionManager,
    private val sources: () -> List<SearchSource>,
    private val batchSize: Int = BATCH_SIZE,
) : JobHandler {
    override val type: String = SEARCH_REBUILD_JOB_TYPE
    private val tx = TransactionTemplate(transactionManager)

    override fun handle(job: Job) {
        val payload = JdbcSearchRebuilds.parse(job.payloadJson) ?: throw PermanentJobFailureException("search-rebuild job ${job.id} has an unreadable payload")
        val source = sources().firstOrNull { it.type == payload.type } ?: throw PermanentJobFailureException("no search source for type ${payload.type}")
        tx.executeWithoutResult {
            val startedAt = rebuilds.touch(payload.type, payload.run) ?: return@executeWithoutResult
            val page = source.page(payload.after, batchSize)
            page.documents.forEach {
                if (it.type != payload.type) throw PermanentJobFailureException("source ${payload.type} returned a document of type ${it.type}")
                engine.reindex(it)
            }
            if (page.next != null) {
                queue.enqueue(SEARCH_REBUILD_JOB_TYPE, JdbcSearchRebuilds.payload(payload.type, payload.run, page.next))
            } else {
                engine.deleteIndexedBefore(payload.type, startedAt)
                rebuilds.finish(payload.type, payload.run)
            }
        }
    }

    companion object {
        const val BATCH_SIZE = 500
    }
}
