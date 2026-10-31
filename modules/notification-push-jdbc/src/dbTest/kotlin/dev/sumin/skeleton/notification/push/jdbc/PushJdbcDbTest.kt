package dev.sumin.skeleton.notification.push.jdbc

import dev.sumin.skeleton.common.erasure.ErasureRequest
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.jobqueue.jdbc.JdbcJobRepository
import dev.sumin.skeleton.jobqueue.jdbc.JobHandler
import dev.sumin.skeleton.jobqueue.jdbc.JobQueueProperties
import dev.sumin.skeleton.jobqueue.jdbc.JobQueueWorker
import dev.sumin.skeleton.notification.push.PushDevice
import dev.sumin.skeleton.notification.push.PushDeviceRegistration
import dev.sumin.skeleton.notification.push.PushDeviceStore
import dev.sumin.skeleton.notification.push.PushJob
import dev.sumin.skeleton.notification.push.PushKinds
import dev.sumin.skeleton.notification.push.PushMessage
import dev.sumin.skeleton.notification.push.PushPlatforms
import dev.sumin.skeleton.notification.push.PushRegistration
import dev.sumin.skeleton.notification.push.PushResult
import dev.sumin.skeleton.notification.push.PushSender
import dev.sumin.skeleton.notification.push.PushTasks
import dev.sumin.skeleton.notification.push.PushTypeMuteStore
import dev.sumin.skeleton.notification.push.PushUrgency
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
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

/** 기기 저장소 · 끄기 저장소 · 작업 어댑터가 두 방언(postgresTest · mysqlTest)에서 같게 구는지 - 같은 소스가 두 DB 에서 돈다. 큐 워커 스레드는 끄고 pollOnce 로 직접 돌린다. */
@Import(DbTestcontainers::class, PushJdbcDbTest.Fixtures::class)
@SpringBootTest(
    classes = [PushJdbcTestApplication::class],
    properties = ["skeleton.job-queue.worker-enabled=false", "skeleton.notification.push.enabled=true"],
)
class PushJdbcDbTest {
    class RecordingSender : PushSender {
        override val kind = PushKinds.WEBPUSH
        val sent = CopyOnWriteArrayList<Pair<PushDevice, PushMessage>>()
        @Volatile var result: PushResult = PushResult.Delivered

        override fun send(device: PushDevice, message: PushMessage): PushResult {
            sent += device to message
            return result
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Fixtures {
        @Bean fun sender() = RecordingSender()
    }

    @Autowired lateinit var store: PushDeviceStore
    @Autowired lateinit var mutes: PushTypeMuteStore
    @Autowired lateinit var tasks: PushTasks
    @Autowired lateinit var erasure: dev.sumin.skeleton.common.erasure.AccountErasureListener
    @Autowired lateinit var sender: RecordingSender
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var repository: JdbcJobRepository
    @Autowired lateinit var properties: JobQueueProperties
    @Autowired lateinit var handlers: List<JobHandler>

    private val base = Instant.parse("2026-10-31T00:00:00Z")

    @BeforeEach
    fun clean() {
        jdbc.sql("delete from push_devices").update()
        jdbc.sql("delete from push_type_mutes").update()
        jdbc.sql("delete from jobs").update()
        sender.sent.clear()
        sender.result = PushResult.Delivered
    }

    private fun register(
        account: String,
        installation: String,
        address: String = "https://push.example.com/send/$installation",
        at: Instant = base,
        max: Int = 20,
    ): PushDevice = store.register(
        PushDeviceRegistration(
            account, installation, "ses_1", PushRegistration(PushKinds.WEBPUSH, PushPlatforms.WEB, address, "p256dh-key", "auth-secret", "Chrome"),
            keyId = "key0123456789abc", now = at, maxDevicesPerAccount = max,
        ),
    )

    private fun deviceCount() = jdbc.sql("select count(*) from push_devices").query(Long::class.java).single()

    @Test
    fun `registering stores the whole row and registering again refreshes the same row`() {
        val first = register("acc_a", "inst_aaaaaaaaaaaaaaaa", at = base)
        val again = register("acc_a", "inst_aaaaaaaaaaaaaaaa", at = base.plusSeconds(60))

        assertEquals(1L, deviceCount())
        assertEquals(first.id, again.id)
        assertEquals(base, again.createdAt)
        assertEquals(base.plusSeconds(60), again.lastSeenAt)
        assertEquals("auth-secret", again.authSecret)
        assertEquals("key0123456789abc", again.keyId)
    }

    @Test
    fun `two concurrent puts of the same installation id leave one row`() {
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val results = (1..8).map { i ->
            pool.submit<PushDevice> {
                start.await()
                register("acc_a", "inst_concurrent_00001", at = base.plusSeconds(i.toLong()))
            }
        }
        start.countDown()
        results.forEach { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1L, deviceCount())
    }

    @Test
    fun `the 21st registration of an account keeps 20 rows and drops the least recently seen`() {
        (0 until 21).forEach { i -> register("acc_a", "inst_cap_%012d".format(i), at = base.plusSeconds(i.toLong())) }

        assertEquals(20L, deviceCount())
        assertNull(store.findByInstallation("acc_a", "inst_cap_%012d".format(0)))
        assertNotNull(store.findByInstallation("acc_a", "inst_cap_%012d".format(20)))
        register("acc_b", "inst_other_0000000001")   // 다른 계정은 영향이 없다
        assertEquals(20, store.listByAccount("acc_a").size)
    }

    @Test
    fun `the same address registered by another installation of another account replaces the old row`() {
        val address = "https://push.example.com/send/shared-endpoint"
        register("acc_a", "inst_aaaaaaaaaaaaaaaa", address)
        val taken = register("acc_b", "inst_bbbbbbbbbbbbbbbb", address)

        assertEquals(1L, deviceCount())
        assertEquals(emptyList(), store.listByAccount("acc_a"))
        assertEquals(listOf(taken.id), store.listByAccount("acc_b").map { it.id })
    }

    @Test
    fun `the same installation registered by another account hands the row over to the new owner`() {
        val old = register("acc_old", "inst_shared_pc_000001", at = base)
        val next = register("acc_new", "inst_shared_pc_000001", at = base.plusSeconds(5))

        assertEquals(old.id, next.id)
        assertEquals("acc_new", store.find(old.id)!!.accountId)
        assertEquals(emptyList(), store.listByAccount("acc_old"))
    }

    @Test
    fun `deleting by installation id removes only the callers own row`() {
        register("acc_a", "inst_aaaaaaaaaaaaaaaa")

        assertEquals(false, store.deleteByInstallation("acc_attacker", "inst_aaaaaaaaaaaaaaaa"))
        assertEquals(1L, deviceCount())
        assertEquals(true, store.deleteByInstallation("acc_a", "inst_aaaaaaaaaaaaaaaa"))
        assertEquals(0L, deviceCount())
    }

    @Test
    fun `session, stale and success bookkeeping touch only what they name`() {
        val kept = register("acc_a", "inst_aaaaaaaaaaaaaaaa", at = base.plusSeconds(100))
        register("acc_a", "inst_bbbbbbbbbbbbbbbb", at = base)
        store.markSuccess(kept.id, base.plusSeconds(200))

        assertEquals(base.plusSeconds(200), store.find(kept.id)!!.lastSuccessAt)
        assertEquals(1, store.deleteStale(base.plusSeconds(50)))   // 안 본 지 오래된 줄만
        assertEquals(listOf(kept.id), store.listByAccount("acc_a").map { it.id })
        assertEquals(0, store.deleteBySession("acc_other", "ses_1"))
        assertEquals(1, store.deleteBySession("acc_a", "ses_1"))
    }

    @Test
    fun `type mutes are per account and per type, idempotent, and erasure clears devices and mutes of that account only`() {
        mutes.setMuted("acc_a", "comment.created", true)
        mutes.setMuted("acc_a", "comment.created", true)
        mutes.setMuted("acc_b", "comment.created", true)
        mutes.setMuted("acc_a", "reward.granted", true)
        register("acc_a", "inst_aaaaaaaaaaaaaaaa")
        register("acc_b", "inst_bbbbbbbbbbbbbbbb")

        assertEquals(setOf("comment.created", "reward.granted"), mutes.mutedTypes("acc_a"))
        assertEquals(setOf("acc_a", "acc_b"), mutes.mutedAmong("comment.created", setOf("acc_a", "acc_b", "acc_c")))
        mutes.setMuted("acc_b", "comment.created", false)
        assertEquals(setOf("acc_a"), mutes.mutedAmong("comment.created", setOf("acc_a", "acc_b")))

        mutes.setMuted("acc_b", "comment.created", true)
        erasure.erase(ErasureRequest("acc_a", "deleted:0123456789abcdef"))

        assertEquals(emptySet(), mutes.mutedTypes("acc_a"))
        assertEquals(emptyList(), store.listByAccount("acc_a"))
        assertEquals(setOf("comment.created"), mutes.mutedTypes("acc_b"))
        assertEquals(1, store.listByAccount("acc_b").size)
    }

    private fun queueWorker() = JobQueueWorker(repository, handlers, properties, TimeProvider.systemUtc())

    private fun job(device: PushDevice, expiresAt: Instant) = PushJob(
        device.id, device.accountId,
        PushMessage("evt_1", "comment.created", "제목", "본문", "/notes/1", "tag-1", PushUrgency.HIGH, Instant.now().minusSeconds(1), expiresAt),
    )

    @Test
    fun `an enqueued push reaches the sender through the queue with its content intact, a held job waits, and Gone deletes the row`() {
        val device = register("acc_a", "inst_aaaaaaaaaaaaaaaa")
        val future = Instant.now().plus(Duration.ofHours(10))
        val job = job(device, Instant.now().plus(Duration.ofHours(1)))

        tasks.enqueue(job, runAt = future)                    // 보류: 때가 안 됐으니 집지 않는다
        assertEquals(0, queueWorker().pollOnce())
        assertTrue(sender.sent.isEmpty())

        jdbc.sql("delete from jobs").update()
        tasks.enqueue(job)
        assertEquals(1, queueWorker().pollOnce())
        assertEquals(job.message, sender.sent.single().second)
        assertNotNull(store.find(device.id)!!.lastSuccessAt)

        sender.result = PushResult.Gone
        tasks.enqueue(job)
        queueWorker().pollOnce()
        assertNull(store.find(device.id))
    }

    @Test
    fun `a Failed verdict keeps the device row and ends the job dead without retrying`() {
        val device = register("acc_a", "inst_aaaaaaaaaaaaaaaa")
        sender.result = PushResult.Failed("unauthorized")
        tasks.enqueue(job(device, Instant.now().plus(Duration.ofHours(1))))

        queueWorker().pollOnce()

        assertNotNull(store.find(device.id))
        assertEquals(1L, jdbc.sql("select count(*) from jobs where status = 'DEAD' and job_type = 'notification.push'").query(Long::class.java).single())
        assertEquals(1, sender.sent.size)
    }
}
