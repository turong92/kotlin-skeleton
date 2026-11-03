package dev.sumin.skeleton.notification.broadcast.jdbc

import dev.sumin.skeleton.common.audience.AudienceParam
import dev.sumin.skeleton.common.audience.AudienceSource
import dev.sumin.skeleton.jobqueue.jdbc.Job
import dev.sumin.skeleton.jobqueue.jdbc.JobStatus
import dev.sumin.skeleton.json.JsonCodec
import dev.sumin.skeleton.notification.DeliveryOutcome
import dev.sumin.skeleton.notification.NotificationChannelDeclaration
import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationSubscriber
import dev.sumin.skeleton.notification.NotificationSubscriptionRegistry
import dev.sumin.skeleton.notification.broadcast.BroadcastAudience
import dev.sumin.skeleton.notification.broadcast.BroadcastContent
import dev.sumin.skeleton.notification.broadcast.BroadcastDeliveryHook
import dev.sumin.skeleton.notification.broadcast.BroadcastErrorCode
import dev.sumin.skeleton.notification.broadcast.BroadcastException
import dev.sumin.skeleton.notification.broadcast.BroadcastStatus
import dev.sumin.skeleton.notification.broadcast.BroadcastStep
import dev.sumin.skeleton.notification.broadcast.BroadcastStepRunner
import dev.sumin.skeleton.notification.broadcast.BroadcastStore
import dev.sumin.skeleton.notification.broadcast.Broadcasts
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * 진짜 DB(postgresTest · mysqlTest)에서 사고 나는 것만: 같은 단계가 두 번 · 동시에 돌아도 알림함 한 벌, 동시에 보내기를 눌러도 하나만 시작, 예약을 풀고 다시 걸면 옛 작업은 무효,
 * 중간에 죽어도 이어 보내면 대상 전원이 정확히 한 번, 취소하면 다음 묶음이 없다, 통계 증가가 동시에 와도 잃지 않는다. 큐 워커는 끄고 단계를 직접 돌린다.
 */
@Import(DbTestcontainers::class, BroadcastJdbcDbTest.Fixtures::class)
@SpringBootTest(
    classes = [BroadcastJdbcTestApplication::class],
    properties = [
        "skeleton.job-queue.worker-enabled=false",
        "skeleton.notification.broadcast.enabled=true", "skeleton.notification.broadcast.types=notice.service",
        "skeleton.notification.broadcast.batch-size=2",
    ],
)
class BroadcastJdbcDbTest {
    class Recorder(registry: NotificationSubscriptionRegistry) {
        val events = CopyOnWriteArrayList<NotificationEvent>()
        init { registry.subscribe(subscriber = NotificationSubscriber { events += it }) }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Fixtures {
        @Bean fun fixed(): AudienceSource = object : AudienceSource {
            private val ids = (1..5).map { "acc_0$it" }
            override val kind = "fixed"
            override val param = AudienceParam.NONE
            override fun validate(param: String?): String? = null
            override fun count(param: String?) = ids.size.toLong()
            override fun page(param: String?, after: String?, limit: Int) = ids.filter { after == null || it > after }.take(limit)
        }
        @Bean fun pushDeclaration() = NotificationChannelDeclaration("push")
        @Bean fun recorder(registry: NotificationSubscriptionRegistry) = Recorder(registry)
    }

    @Autowired lateinit var broadcasts: Broadcasts
    @Autowired lateinit var runner: BroadcastStepRunner
    @Autowired lateinit var store: BroadcastStore
    @Autowired lateinit var deadListener: BroadcastDeadListener
    @Autowired lateinit var hook: BroadcastDeliveryHook
    @Autowired lateinit var recorder: Recorder
    @Autowired lateinit var json: JsonCodec
    @Autowired lateinit var jdbc: JdbcClient

    private fun draft(channels: List<String> = emptyList()): String =
        broadcasts.create("acc_op", BroadcastContent("notice.service", "Hello", "Body", null, channels, BroadcastAudience("fixed"))).id

    private fun steps(id: String): List<BroadcastStep> =
        jdbc.sql("select payload_json from jobs where job_type = '$BROADCAST_STEP_JOB_TYPE' order by id").query(String::class.java).list()
            .map { json.fromDocument(json.parse(checkNotNull(it)), BroadcastStep::class) }.filter { it.id == id }

    private fun inboxRows(id: String) = jdbc.sql("select count(*) from notification_inbox where event_id = :id").param("id", id).query(Long::class.java).single()
    private fun inboxPeople(id: String) = jdbc.sql("select count(distinct recipient_id) from notification_inbox where event_id = :id").param("id", id).query(Long::class.java).single()

    private fun current(id: String) = checkNotNull(store.find(id))

    private fun drain(id: String) {
        while (current(id).status.let { it == BroadcastStatus.SCHEDULED || it == BroadcastStatus.SENDING }) {
            val b = current(id)
            runner.run(BroadcastStep(id, checkNotNull(b.runToken), b.nextSeq))
        }
    }

    @Test
    fun `the same step run twice at the same time writes one inbox row per person, publishes once and counts once`() {
        val id = draft()
        broadcasts.send("acc_op", id, null)
        val step = steps(id).single()
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val runs = (1..2).map { pool.submit { start.await(); runner.run(step) } }
        start.countDown()
        runs.forEach { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()
        runner.run(step)   // 재시도

        assertEquals(2, inboxRows(id))
        assertEquals(1, recorder.events.count { it.id == id })
        assertEquals(2, current(id).recipients)
        assertEquals(1, current(id).nextSeq)
        assertEquals(listOf(0, 1), steps(id).map { it.seq })   // 보내기가 넣은 첫 작업 + 다음 작업 한 번
    }

    @Test
    fun `sending at the same time starts once`() {
        val id = draft()
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val results = (1..2).map { pool.submit<Boolean> { start.await(); runCatching { broadcasts.send("acc_op", id, null) }.fold({ true }, { e -> assertEquals(BroadcastErrorCode.INVALID_STATE, (e as BroadcastException).errorCode); false }) } }
        start.countDown()
        assertEquals(1, results.count { it.get(30, TimeUnit.SECONDS) })
        pool.shutdown()
        assertEquals(1, steps(id).size)
    }

    @Test
    fun `unscheduling and scheduling again leaves the old job doing nothing`() {
        val id = draft()
        broadcasts.send("acc_op", id, Instant.now().plusSeconds(3600))
        val old = steps(id).single()
        broadcasts.unschedule(id)
        broadcasts.send("acc_op", id, Instant.now().plusSeconds(7200))
        runner.run(old)
        assertEquals(0, inboxRows(id))
        assertEquals(BroadcastStatus.SCHEDULED, current(id).status)
        drain(id)
        assertEquals(5, inboxRows(id))
    }

    @Test
    fun `a broadcast that dies half way resumes and reaches everyone exactly once`() {
        val id = draft()
        broadcasts.send("acc_op", id, null)
        runner.run(steps(id).single())                       // acc_01 acc_02
        val dying = steps(id).last()
        deadListener.onDead(deadJob(dying), "java.lang.IllegalStateException: boom for acc_03")
        assertEquals(BroadcastStatus.FAILED, current(id).status)
        assertEquals("java.lang.IllegalStateException", current(id).failureReason)   // 메시지(받는 사람이 섞일 수 있다)는 남기지 않는다
        runner.run(dying)                                    // 죽은 뒤 늦게 돈 옛 작업
        assertEquals(2, inboxRows(id))

        broadcasts.resume("acc_op", id)
        runner.run(dying)                                    // 새 토큰이 있으니 옛 토큰은 무효
        assertEquals(2, inboxRows(id))
        drain(id)

        assertEquals(BroadcastStatus.SENT, current(id).status)
        assertEquals(5, inboxRows(id))
        assertEquals(5, inboxPeople(id))
        assertEquals(5, current(id).recipients)
        assertEquals(listOf(2, 2, 1), recorder.events.filter { it.id == id }.map { it.recipientIds.size })
    }

    @Test
    fun `cancelling stops the next batch and halts the queued pushes`() {
        val id = draft()
        broadcasts.send("acc_op", id, null)
        runner.run(steps(id).single())
        broadcasts.cancel("acc_op", id)
        runner.run(steps(id).last())
        assertEquals(2, inboxRows(id))
        assertEquals(BroadcastStatus.CANCELLED, current(id).status)
        assertTrue(hook.halted(id))
    }

    @Test
    fun `delivery counts from many threads are not lost`() {
        val id = draft(channels = listOf("push"))
        broadcasts.send("acc_op", id, null)
        val pool = Executors.newFixedThreadPool(8)
        (1..8).map { pool.submit { repeat(25) { hook.onOutcome(id, "push", DeliveryOutcome.DELIVERED); hook.onOpened(id, "push") } } }.forEach { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()
        hook.onQueued(id, "push", 7)
        val push = store.channelStats(id).getValue("push")
        assertEquals(200, push.delivered)
        assertEquals(200, push.opened)
        assertEquals(7, push.queued)
    }

    private fun deadJob(step: BroadcastStep) = Job(
        id = 1, type = BROADCAST_STEP_JOB_TYPE, payloadJson = json.stringify(json.toDocument(step)), status = JobStatus.DEAD, attempts = 5, maxAttempts = 5,
        nextRunAt = Instant.now(), lockedBy = null, lockedAt = null, lastError = null, createdAt = Instant.now(), updatedAt = Instant.now(),
    )
}
