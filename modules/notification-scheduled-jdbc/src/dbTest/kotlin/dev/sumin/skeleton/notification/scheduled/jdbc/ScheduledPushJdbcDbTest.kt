package dev.sumin.skeleton.notification.scheduled.jdbc

import dev.sumin.skeleton.notification.scheduled.ScheduleResult
import dev.sumin.skeleton.notification.scheduled.ScheduledPush
import dev.sumin.skeleton.notification.scheduled.ScheduledPushStatus
import dev.sumin.skeleton.notification.scheduled.ScheduledPushStore
import dev.sumin.skeleton.notification.scheduled.SkipReason
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/**
 * 진짜 DB(postgresTest · mysqlTest)에서 사고 나는 것만: 같은 키는 한 줄(바꾸기 · 나간 뒤에는 그대로), 여러 인스턴스가 동시에 집어도 한 줄은 한 번만,
 * 집은 쪽이 죽으면 임대가 끝난 뒤 다시 집힘, 취소는 안 나간 줄만, 탈퇴 삭제 · 하루 상한 집계 · 끝난 줄 정리.
 */
@Import(DbTestcontainers::class)
@SpringBootTest(
    classes = [ScheduledPushJdbcTestApplication::class],
    properties = ["skeleton.notification.scheduled.enabled=true", "skeleton.notification.scheduled.type=reminder"],
)
class ScheduledPushJdbcDbTest {
    @Autowired lateinit var store: ScheduledPushStore

    private val t0 = Instant.parse("2026-11-02T03:00:00.123456Z")
    private val lease = Duration.ofMinutes(5)
    private var seq = 0

    private fun push(key: String, to: String = "acc_1", at: Instant = t0, title: String = "제목", ad: Boolean = false) =
        ScheduledPush(key, to, at, title, "본문", "/s/1", ad)

    /** 시험끼리 키가 겹치지 않게 - 한 DB 를 같이 쓴다 */
    private fun unique(name: String) = "$name-${System.nanoTime()}-${seq++}"

    @Test
    fun `the jdbc store is the one wired`() {
        assertIs<JdbcScheduledPushStore>(store)
    }

    @Test
    fun `the same key keeps one row - pending is replaced, a finished key is left alone`() {
        val key = unique("k")
        assertEquals(ScheduleResult.CREATED, store.upsert(push(key, title = "처음"), t0))
        assertEquals(ScheduleResult.REPLACED, store.upsert(push(key, title = "바뀜", at = t0.plusSeconds(30)), t0))
        val claimed = store.claimDue(t0.plusSeconds(60), lease, 1000).single { it.push.key == key }
        assertEquals("바뀜", claimed.push.title)
        assertEquals(t0.plusSeconds(30), claimed.push.runAt)   // 마이크로초 시각이 그대로 오간다
        assertEquals(ScheduleResult.IGNORED, store.upsert(push(key, title = "집혀 나가는 중"), t0.plusSeconds(61)))
        store.finish(key, ScheduledPushStatus.SENT, null, t0.plusSeconds(62))
        assertEquals(ScheduleResult.IGNORED, store.upsert(push(key, title = "이미 나간 키"), t0.plusSeconds(63)))
    }

    @Test
    fun `many sweepers claiming at once get each due row exactly once`() {
        val to = unique("acc")
        repeat(30) { store.upsert(push(unique("c"), to = to), t0) }
        val pool = Executors.newFixedThreadPool(8)
        val claimed = try {
            (1..8).map { pool.submit<List<String>> { store.claimDue(t0.plusSeconds(1), lease, 100).filter { it.push.recipientId == to }.map { it.push.key } } }.flatMap { it.get() }
        } finally {
            pool.shutdown()
        }
        assertEquals(30, claimed.size)
        assertEquals(30, claimed.toSet().size)
    }

    @Test
    fun `a claimed row comes back only after the lease, and a rescheduled one waits for its new time`() {
        val key = unique("l")
        val to = unique("acc")
        store.upsert(push(key, to = to), t0)
        assertEquals(1, store.claimDue(t0, lease, 1000).count { it.push.recipientId == to })
        assertEquals(0, store.claimDue(t0.plusSeconds(60), lease, 1000).count { it.push.recipientId == to })   // 임대 안
        val again = store.claimDue(t0.plus(lease).plusSeconds(1), lease, 1000).single { it.push.recipientId == to }
        assertEquals(2, again.attempts)

        store.reschedule(key, t0.plus(Duration.ofHours(10)), t0.plus(lease).plusSeconds(2))
        assertEquals(0, store.claimDue(t0.plusSeconds(3600), lease, 1000).count { it.push.recipientId == to })
        assertEquals(1, store.claimDue(t0.plus(Duration.ofHours(10)).plusSeconds(1), lease, 1000).count { it.push.recipientId == to })
    }

    @Test
    fun `cancel removes only a pending row`() {
        val pending = unique("p")
        val done = unique("d")
        store.upsert(push(pending), t0)
        store.upsert(push(done), t0)
        store.finish(done, ScheduledPushStatus.SENT, null, t0)   // 집지 않고 닫으려 해도 PENDING 이라 닫힌다
        assertTrue(store.cancel(pending))
        assertEquals(false, store.cancel(pending))
        assertEquals(false, store.cancel(done))
    }

    @Test
    fun `sent count, erasure, purge and stats`() {
        val who = unique("acc")
        val other = unique("acc")
        val sent1 = unique("s1")
        val sent2 = unique("s2")
        val skipped = unique("sk")
        listOf(sent1, sent2, skipped).forEach { store.upsert(push(it, to = who), t0) }
        store.upsert(push(unique("o"), to = other), t0)
        store.finish(sent1, ScheduledPushStatus.SENT, null, t0.minus(Duration.ofHours(30)))
        store.finish(sent2, ScheduledPushStatus.SENT, null, t0.minusSeconds(60))
        store.finish(skipped, ScheduledPushStatus.SKIPPED, SkipReason.DAILY_LIMIT, t0)
        assertEquals(1, store.sentCountSince(who, t0.minus(Duration.ofHours(24))))   // 24시간 안은 하나
        assertEquals(2, store.sentCountSince(who, t0.minus(Duration.ofHours(48))))
        val stats = store.stats()
        assertTrue(stats.sent >= 2 && stats.skipped >= 1 && stats.pending >= 1)

        assertEquals(1, store.purgeFinishedBefore(t0.minus(Duration.ofHours(24))))   // 30시간 전에 끝난 하나
        assertEquals(2, store.deleteByRecipient(who))
        assertEquals(0, store.deleteByRecipient(who))   // 멱등
        assertEquals(0, store.sentCountSince(who, t0.minus(Duration.ofDays(30))))
        assertEquals(1, store.claimDue(t0, lease, 1000).count { it.push.recipientId == other })   // 남의 예약은 그대로
    }
}
