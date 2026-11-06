package dev.sumin.skeleton.point.jdbc

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.jobqueue.jdbc.JobQueue
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import dev.sumin.skeleton.point.PointAutoConfiguration
import dev.sumin.skeleton.point.PointOperationScheduler
import dev.sumin.skeleton.point.PointOperations
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.PlatformTransactionManager

/** 조립: 작업 기록 층은 늘 서고, 불명 건 조회(예약기 · 처리기 · 죽은 작업 고리)는 큐가 있을 때만 서며, 설정 `skeleton.point.operations.reconcile.*` 를 따른다 */
class PointOperationsWiringTest {
    private object Dialect : SqlDialect {
        override val vendor = "h2"
        override fun instantParam(value: Instant?): Any? = value
        override fun readInstant(rs: java.sql.ResultSet, column: String): Instant? = null
        override fun insertIgnore(table: String, columns: List<String>, conflictColumns: List<String>) = ""
    }

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PointJdbcAutoConfiguration::class.java, PointOperationJobsAutoConfiguration::class.java, PointAutoConfiguration::class.java))
        .withBean(DataSource::class.java, { DriverManagerDataSource("jdbc:h2:mem:point-wiring") })
        .withBean(PlatformTransactionManager::class.java, { DataSourceTransactionManager(DriverManagerDataSource("jdbc:h2:mem:point-wiring")) })
        .withBean(SqlDialect::class.java, { Dialect })
        .withBean(TimeProvider::class.java, { TimeProvider.systemUtc() })

    @Test
    fun `without a job queue the operations layer stands and nothing about reconcile jobs does`() {
        runner.run {
            assertTrue(it.startupFailure == null, it.startupFailure?.toString())
            assertEquals(1, it.getBeansOfType(PointOperations::class.java).size)
            assertTrue(it.getBeansOfType(PointOperationScheduler::class.java).isEmpty())
            assertTrue(it.getBeansOfType(PointOperationResolveHandler::class.java).isEmpty())
        }
    }

    @Test
    fun `with a job queue the scheduler, the handler and the dead-job hook stand and follow the reconcile settings`() {
        val enqueued = mutableListOf<List<Any?>>()
        val queue = object : JobQueue {
            override fun enqueue(type: String, payloadJson: String, runAt: Instant?, maxAttempts: Int?): Long {
                enqueued += listOf(type, payloadJson, runAt, maxAttempts)
                return 1
            }
        }
        runner.withBean(JobQueue::class.java, { queue }).withPropertyValues("skeleton.point.operations.reconcile.first-delay=90s", "skeleton.point.operations.reconcile.max-attempts=5").run {
            assertTrue(it.startupFailure == null, it.startupFailure?.toString())
            assertEquals(1, it.getBeansOfType(PointOperationResolveHandler::class.java).size)
            assertEquals(1, it.getBeansOfType(PointOperationDeadListener::class.java).size)
            it.getBean(PointOperationScheduler::class.java).schedule("op_1", Instant.parse("2026-11-07T00:00:00Z"))
            assertEquals(listOf<List<Any?>>(listOf(POINT_OPERATION_JOB_TYPE, """{"operationId":"op_1"}""", Instant.parse("2026-11-07T00:00:00Z"), 5)), enqueued)
        }
    }

    @Test
    fun `the reconcile settings default to 60 seconds and 12 attempts and refuse nonsense`() {
        runner.run {
            val reconcile = it.getBean(dev.sumin.skeleton.point.PointProperties::class.java).operations.reconcile
            assertEquals(java.time.Duration.ofSeconds(60), reconcile.firstDelay)
            assertEquals(12, reconcile.maxAttempts)
        }
        runner.withPropertyValues("skeleton.point.operations.reconcile.max-attempts=0").run { assertTrue(it.startupFailure != null) }
    }
}
