package dev.sumin.skeleton.jobqueue.jdbc

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

class SlowHandler(override val type: String) : JobHandler {
    val ids = CopyOnWriteArrayList<Long>()
    private val running = AtomicInteger()
    val maxRunning = AtomicInteger()
    override fun handle(job: Job) {
        maxRunning.accumulateAndGet(running.incrementAndGet()) { a, b -> maxOf(a, b) }
        try {
            Thread.sleep(150)
            ids += job.id
        } finally {
            running.decrementAndGet()
        }
    }
}

@TestConfiguration(proxyBeanMethods = false)
class SlowHandlerFixtures {
    @Bean fun slow() = SlowHandler("slow")
}

abstract class FastPathBase {
    @Autowired lateinit var queue: JobQueue
    @Autowired lateinit var repository: JdbcJobRepository
    @Autowired lateinit var handler: SlowHandler
    @Autowired lateinit var transactionManager: PlatformTransactionManager

    protected fun inOneTransaction(count: Int): List<Long> =
        TransactionTemplate(transactionManager).execute { (1..count).map { queue.enqueue("slow", """{"n":$it}""") } }!!

    protected fun eventually(what: String, millis: Long = 8_000, check: () -> Boolean) {
        val deadline = System.nanoTime() + millis * 1_000_000
        while (!check()) {
            check(System.nanoTime() < deadline) { "timed out waiting for: $what" }
            Thread.sleep(20)
        }
    }
}

/**
 * 일꾼의 빠른 길 - 넣자마자(커밋 뒤) 깨우기 / 가득 찬 묶음은 기다리지 않고 바로 다시 집기.
 * 폴링 간격을 한 시간으로 늘려 두므로 아래 일들이 제때 끝나면 폴링이 아니라 깨우기 / 이어 집기 덕이다. 일꾼 스레드 하나(기본).
 */
@Import(DbTestcontainers::class, SlowHandlerFixtures::class)
@SpringBootTest(
    classes = [JobQueueTestApplication::class],
    properties = ["skeleton.job-queue.poll-interval=1h", "skeleton.job-queue.batch-size=2"],
)
class JobQueueWakeIntegrationTest : FastPathBase() {
    @Test
    fun `커밋된 잡은 폴링을 기다리지 않고 바로 돌고 커밋 전에는 돌지 않는다`() {
        var id = 0L
        TransactionTemplate(transactionManager).execute {
            id = queue.enqueue("slow", """{"n":1}""")
            Thread.sleep(400) // 커밋 전에는 일꾼이 깨어도 이 줄이 안 보인다 - 깨우기는 커밋 뒤에 나가야 한다
            assertEquals(0, handler.ids.size)
        }
        eventually("the committed job runs without the 1h poll") { repository.findById(id)?.status == JobStatus.DONE }
    }

    @Test
    fun `가득 찬 묶음이면 기다리지 않고 바로 다시 집는다`() {
        // 한 트랜잭션의 커밋 한 번에 깨우기가 몰려 오지만 표는 스레드 수(1)까지만 쌓인다 - 묶음(2건)을 넘는 몫은 이어 집기가 맡는다
        val ids = inOneTransaction(7)
        eventually("7 jobs drained with batch-size 2 and one thread") { ids.all { repository.findById(it)?.status == JobStatus.DONE } }
        assertTrue(handler.ids.containsAll(ids), "not all ran: ${handler.ids}")
    }
}

/** 동시 처리 - 일꾼 스레드 넷이 같은 표를 보아도 같은 잡을 둘이 집지 않는다. */
@Import(DbTestcontainers::class, SlowHandlerFixtures::class)
@SpringBootTest(
    classes = [JobQueueTestApplication::class],
    properties = ["skeleton.job-queue.poll-interval=1h", "skeleton.job-queue.batch-size=2", "skeleton.job-queue.concurrency=4"],
)
class JobQueueConcurrencyIntegrationTest : FastPathBase() {
    @Test
    fun `일꾼 넷이 같은 잡을 둘이 집지 않고 실제로 겹쳐 돈다`() {
        val ids = inOneTransaction(16)
        eventually("16 jobs drained by 4 workers") { ids.all { repository.findById(it)?.status == JobStatus.DONE } }
        assertEquals(16, handler.ids.size, "a job ran twice or was lost: ${handler.ids}")
        assertEquals(ids.toSet(), handler.ids.toSet())
        assertTrue(handler.maxRunning.get() >= 2, "expected overlapping handlers, saw ${handler.maxRunning.get()}")
    }
}
