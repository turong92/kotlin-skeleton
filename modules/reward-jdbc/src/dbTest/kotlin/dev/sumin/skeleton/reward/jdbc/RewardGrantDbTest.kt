package dev.sumin.skeleton.reward.jdbc

import dev.sumin.skeleton.common.reward.IssuedReward
import dev.sumin.skeleton.common.reward.RewardGrantView
import dev.sumin.skeleton.common.reward.RewardIssuer
import dev.sumin.skeleton.common.reward.RewardLabel
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.jobqueue.jdbc.JdbcJobRepository
import dev.sumin.skeleton.jobqueue.jdbc.JobHandler
import dev.sumin.skeleton.jobqueue.jdbc.JobQueueProperties
import dev.sumin.skeleton.jobqueue.jdbc.JobQueueWorker
import dev.sumin.skeleton.reward.RewardErrorCode
import dev.sumin.skeleton.reward.RewardException
import dev.sumin.skeleton.reward.RewardGrantService
import dev.sumin.skeleton.reward.RewardGrantStatus
import dev.sumin.skeleton.reward.RewardGrantStore
import dev.sumin.skeleton.reward.RewardGrants
import dev.sumin.skeleton.reward.RewardWorker
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/** 지급 건이 두 방언(postgresTest · mysqlTest)에서 정확히 한 번 지급되는지 - 같은 소스가 두 DB 에서 돈다. 큐 워커 스레드는 끄고 pollOnce 로 직접 돌린다. */
@Import(DbTestcontainers::class, RewardGrantDbTest.Fixtures::class)
@SpringBootTest(
    classes = [RewardJdbcTestApplication::class],
    properties = ["skeleton.job-queue.worker-enabled=false", "skeleton.job-queue.max-attempts=1"],
)
class RewardGrantDbTest {
    /** 앞의 [failures] 번은 던지고, 성공은 [issuedRefs] 에 남긴다. 들어온 순간 [onEnter] 로 겹침을 만든다 */
    class TestIssuer : RewardIssuer {
        override val type = "point"
        @Volatile var failures = 0
            @Volatile var onEnter: () -> Unit = {}
        val issued = AtomicInteger()
        override fun validate(params: Map<String, String>) = Unit
        override fun issue(grant: RewardGrantView): IssuedReward {
            onEnter()
            if (failures > 0) {
                failures--
                error("boom")
            }
            issued.incrementAndGet()
            return IssuedReward("res_${grant.id}")
        }
        override fun describe(params: Map<String, String>, locale: Locale) = RewardLabel("Points", "p")
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Fixtures {
        @Bean fun issuer() = TestIssuer()
    }

    @Autowired lateinit var store: RewardGrantStore
    @Autowired lateinit var grants: RewardGrants
    @Autowired lateinit var service: RewardGrantService
    @Autowired lateinit var worker: RewardWorker
    @Autowired lateinit var issuer: TestIssuer
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var transactionManager: PlatformTransactionManager
    @Autowired lateinit var repository: JdbcJobRepository
    @Autowired lateinit var properties: JobQueueProperties
    @Autowired lateinit var handlers: List<JobHandler>
    @Autowired lateinit var deadListener: RewardDeadListener

    @BeforeEach
    fun clean() {
        jdbc.sql("delete from reward_grants").update()
        jdbc.sql("delete from jobs").update()
        issuer.failures = 0
        issuer.onEnter = {}
        issuer.issued.set(0)
    }

    private fun record(key: String = "c1:acc_1:2026-10-28:daily:0", account: String = "acc_1"): String? =
        store.inTransaction { grants.record(account, "point", mapOf("amount" to "100"), "attendance", key) }

    private fun jobCount() = jdbc.sql("select count(*) from jobs where job_type = 'reward.issue'").query(Long::class.java).single()
    private fun queueWorker() = JobQueueWorker(repository, handlers, properties, TimeProvider.systemUtc(), deadListeners = listOf(deadListener))

    @Test
    fun `the same source recorded twice is one grant and one job`() {
        val first = record()
        val second = record()

        assertNotNull(first)
        assertNull(second)
        assertEquals(1L, jdbc.sql("select count(*) from reward_grants").query(Long::class.java).single())
        assertEquals(1L, jobCount())
        assertEquals(mapOf("amount" to "100"), store.find(first)!!.params)
    }

    @Test
    fun `record outside a transaction is refused and a rollback takes the job away with the row`() {
        assertFailsWith<IllegalStateException> { grants.record("acc_1", "point", emptyMap(), "attendance", "k0") }

        runCatching {
            TransactionTemplate(transactionManager).execute {
                grants.record("acc_1", "point", emptyMap(), "attendance", "k1")
                error("roll back")
            }
        }

        assertEquals(0L, jdbc.sql("select count(*) from reward_grants").query(Long::class.java).single())
        assertEquals(0L, jobCount())
    }

    @Test
    fun `two workers on the same grant issue once`() {
        val id = record()!!
        val entered = CountDownLatch(1)
        issuer.onEnter = { entered.countDown(); Thread.sleep(400) }   // 첫 일꾼이 잠근 채 머무는 동안 둘째가 온다
        val pool = Executors.newFixedThreadPool(2)
        val first = pool.submit { worker.process(id) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        val second = pool.submit { worker.process(id) }
        first.get(30, TimeUnit.SECONDS)
        second.get(30, TimeUnit.SECONDS)
        pool.shutdown()

        assertEquals(1, issuer.issued.get())
        assertEquals(RewardGrantStatus.ISSUED, store.find(id)!!.status)
    }

    @Test
    fun `a dead job leaves the grant failed and issuing again after a retry pays once`() {
        issuer.failures = 1
        val id = record()!!

        assertEquals(1, queueWorker().pollOnce())   // max-attempts=1 - 첫 실패가 DEAD
        val failed = store.find(id)!!
        assertEquals(RewardGrantStatus.FAILED, failed.status)
        assertEquals(0, issuer.issued.get())

        service.retry(id)
        assertEquals(RewardGrantStatus.PENDING, store.find(id)!!.status)
        assertEquals(1, queueWorker().pollOnce())

        assertEquals(RewardGrantStatus.ISSUED, store.find(id)!!.status)
        assertEquals(1, issuer.issued.get())
        assertNull(store.find(id)!!.lastError)
    }

    @Test
    fun `retry and cancel only move the states they are meant for`() {
        val id = record()!!
        assertFailsWith<RewardException> { service.retry(id) }.also { assertEquals(RewardErrorCode.NOT_RETRYABLE.code, (it.errorCode).code) }
        service.cancel(id)
        assertEquals(RewardGrantStatus.CANCELED, store.find(id)!!.status)
        assertFailsWith<RewardException> { service.cancel(id) }
        worker.process(id)   // 취소된 건은 지급기에 가지 않는다
        assertEquals(0, issuer.issued.get())
    }

    @Test
    fun `page lists newest first with a cursor and a status filter, and an erased account loses its grants`() {
        val ids = (1..3).map { record("k$it", account = if (it == 3) "acc_2" else "acc_1")!! }
        worker.process(ids[0])

        assertEquals(ids.reversed(), store.page(null, null, 10).map { it.id })
        assertEquals(listOf(ids[1], ids[0]), store.page(null, ids[2], 10).map { it.id })
        assertEquals(listOf(ids[0]), store.page(RewardGrantStatus.ISSUED, null, 10).map { it.id })

        assertEquals(2, store.deleteByAccount("acc_1"))
        assertEquals(listOf(ids[2]), store.page(null, null, 10).map { it.id })
    }
}
