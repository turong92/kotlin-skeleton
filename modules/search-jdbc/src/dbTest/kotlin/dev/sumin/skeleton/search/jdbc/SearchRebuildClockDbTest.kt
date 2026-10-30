package dev.sumin.skeleton.search.jdbc

import dev.sumin.skeleton.common.search.SearchDocument
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.jobqueue.jdbc.Job
import dev.sumin.skeleton.jobqueue.jdbc.JobQueue
import dev.sumin.skeleton.jobqueue.jdbc.JobStatus
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager

/**
 * 재색인을 시작한 인스턴스와 쪽을 처리한 인스턴스의 시계가 다르다 - 시작 쪽 시계가 한 시간 앞서 있어도 끝 정리가 방금 다시 넣은 문서를 지우지 않는다.
 * 큐 워커를 끄고 처리기를 직접 부른다(두 인스턴스를 흉내 내려고 서로 다른 시계를 가진 객체를 따로 만든다).
 */
@Import(DbTestcontainers::class)
@SpringBootTest(classes = [SearchJdbcTestApplication::class], properties = ["skeleton.job-queue.worker-enabled=false"])
class SearchRebuildClockDbTest {
    @Autowired lateinit var dataSource: DataSource
    @Autowired lateinit var transactionManager: PlatformTransactionManager
    @Autowired lateinit var dialect: SqlDialect
    @Autowired lateinit var queue: JobQueue
    @Autowired lateinit var jdbc: JdbcClient

    @Test
    fun `a rebuild started by a node whose clock runs ahead does not wipe the documents another node reindexed`() {
        val template = NamedParameterJdbcTemplate(dataSource)
        val ahead = TimeProvider { Instant.now().plus(Duration.ofHours(1)) }
        val starter = JdbcSearchRebuilds(template, transactionManager, dialect, ahead, queue, Duration.ofMinutes(15))
        val engine = JdbcSearchEngine(template, transactionManager, dialect)
        val source = MutableSource("clock-one").also { src ->
            src.documents += (1..3).map { SearchDocument("clock-one", "d$it", "가방끈 $it", "", null, null, Instant.parse("2026-10-30T00:00:00Z")) }
        }
        val handler = SearchRebuildJobHandler(engine, starter, queue, transactionManager, { listOf(source) })

        assertTrue(starter.start("clock-one"))
        val run = jdbc.sql("select run_id from search_rebuilds where doc_type = 'clock-one'").query(String::class.java).single()
        val job = Job(1, SEARCH_REBUILD_JOB_TYPE, JdbcSearchRebuilds.payload("clock-one", run, null), JobStatus.RUNNING, 1, 3, Instant.now(), null, null, null, Instant.now(), Instant.now())
        handler.handle(job)

        assertEquals(3, jdbc.sql("select count(*) from search_documents where doc_type = 'clock-one'").query(Long::class.java).single())
    }
}
