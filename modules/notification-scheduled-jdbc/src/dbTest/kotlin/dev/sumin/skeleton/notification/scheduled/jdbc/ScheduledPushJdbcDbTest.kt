package dev.sumin.skeleton.notification.scheduled.jdbc

import dev.sumin.skeleton.notification.scheduled.DuePush
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
 * 진짜 DB(postgresTest · mysqlTest)에서 사고 나는 것만: 같은 키는 한 줄(바꾸기 · 나간 뒤에는 그대로 · 같은 키 동시 예약), 여러 인스턴스가 동시에 집어도 한 줄은 한 번만,
 * 집은 쪽이 죽으면 임대가 끝난 뒤 다시 집힘 + 옛 집기의 닫기는 듣지 않음, 취소는 안 나간 줄만, 탈퇴 삭제 · 하루 상한 집계 · 끝난 줄 정리.
 */
@Import(DbTestcontainers::class)
@SpringBootTest(
    classes = [ScheduledPushJdbcTestApplication::class],
    properties = ["skeleton.job-queue.mode=on-request", "skeleton.notification.scheduled.enabled=true", "skeleton.notification.scheduled.type=reminder"],
)
class ScheduledPushJdbcDbTest {
    @Autowired lateinit var store: ScheduledPushStore

    private val t0 = Instant.parse("2026-11-02T03:00:00.123456Z")
    private val lease = Duration.ofMinutes(5)
    private val window = Duration.ofDays(30)
    private var seq = 0

    private fun push(key: String, to: String = "acc_1", at: Instant = t0, title: String = "제목", ad: Boolean = false) =
        ScheduledPush(key, to, at, title, "본문", "/s/1", ad)

    private fun up(push: ScheduledPush, now: Instant = t0) = store.upsert(push, now, window)

    /** 시험끼리 키가 겹치지 않게 - 한 DB 를 같이 쓴다 */
    private fun unique(name: String) = "$name-${System.nanoTime()}-${seq++}"

    /** 그 키만 집어 닫을 수 있는 증표를 얻는다 */
    private fun claim(key: String, now: Instant = t0): DuePush = store.claimDue(now, lease, 10_000).single { it.push.key == key }

    @Test
    fun `the jdbc store is the one wired`() {
        assertIs<JdbcScheduledPushStore>(store)
    }

    @Test
    fun `the same key keeps one row - pending is replaced, a recently finished key is left alone`() {
        val key = unique("k")
        assertEquals(ScheduleResult.CREATED, up(push(key, title = "처음")))
        assertEquals(ScheduleResult.REPLACED, up(push(key, title = "바뀜", at = t0.plusSeconds(30))))
        val claimed = claim(key, t0.plusSeconds(60))
        assertEquals("바뀜", claimed.push.title)
        assertEquals(t0.plusSeconds(30), claimed.push.runAt)   // 마이크로초 시각이 그대로 오간다
        assertEquals(ScheduleResult.IGNORED, up(push(key, title = "집혀 나가는 중"), t0.plusSeconds(61)))
        store.finish(key, claimed.claimToken, ScheduledPushStatus.SENT, null, t0.plusSeconds(62))
        assertEquals(ScheduleResult.IGNORED, up(push(key, title = "이미 나간 키"), t0.plusSeconds(63)))
    }

    @Test
    fun `a finished key becomes a new reservation after the dedupe window`() {
        val key = unique("w")
        up(push(key))
        store.finish(key, claim(key).claimToken, ScheduledPushStatus.SENT, null, t0)
        assertEquals(ScheduleResult.IGNORED, up(push(key, title = "창 안"), t0.plus(window).minusSeconds(1)))
        assertEquals(ScheduleResult.CREATED, up(push(key, title = "창 밖", at = t0.plus(window).plusSeconds(5)), t0.plus(window).plusSeconds(1)))
        assertEquals("창 밖", claim(key, t0.plus(window).plusSeconds(10)).push.title)
    }

    @Test
    fun `the same key reserved by many callers at once leaves exactly one row and no error`() {
        val key = unique("race")
        val pool = Executors.newFixedThreadPool(8)
        try {
            (1..16).map { i -> pool.submit<ScheduleResult> { up(push(key, title = "t$i")) } }.forEach { it.get() }   // 던지면 get() 이 터진다
        } finally {
            pool.shutdown()
        }
        assertTrue(claim(key).push.title.startsWith("t"))   // 한 줄이다 - single 이 실패하지 않는다
    }

    @Test
    fun `many sweepers claiming at once get each due row exactly once`() {
        val to = unique("acc")
        repeat(30) { up(push(unique("c"), to = to)) }
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
    fun `a claimed row comes back only after the lease, and the old claim can no longer close or move it`() {
        val key = unique("l")
        val to = unique("acc")
        up(push(key, to = to))
        val first = claim(key)
        assertEquals(0, store.claimDue(t0.plusSeconds(60), lease, 10_000).count { it.push.recipientId == to })   // 임대 안
        val later = t0.plus(lease).plusSeconds(1)
        val second = claim(key, later)
        assertEquals(2, second.attempts)

        // 임대가 끝나 다시 집힌 뒤 옛 훑기가 늦게 닫으려 해도 아무 일도 없다
        store.finish(key, first.claimToken, ScheduledPushStatus.SENT, null, later)
        store.reschedule(key, first.claimToken, later.plusSeconds(999), later)
        assertEquals(0, store.sentCountSince(to, t0.minus(Duration.ofDays(1))))

        store.reschedule(key, second.claimToken, t0.plus(Duration.ofHours(10)), later)
        assertEquals(0, store.claimDue(t0.plusSeconds(3600), lease, 10_000).count { it.push.recipientId == to })
        assertEquals(1, store.claimDue(t0.plus(Duration.ofHours(10)).plusSeconds(1), lease, 10_000).count { it.push.recipientId == to })
    }

    @Test
    fun `replacing an expired claim drops it so the old sweeper cannot mark the new content as sent`() {
        val key = unique("r")
        val to = unique("acc")
        up(push(key, to = to, title = "옛 내용"))
        val old = claim(key)
        val later = t0.plus(lease).plusSeconds(1)   // 임대 끝 - 앱이 예약을 바꾼다
        assertEquals(ScheduleResult.REPLACED, up(push(key, to = to, title = "새 내용", at = later), later))
        store.finish(key, old.claimToken, ScheduledPushStatus.SENT, null, later)   // 옛 훑기의 늦은 닫기
        assertEquals(0, store.sentCountSince(to, t0.minus(Duration.ofDays(1))))
        assertEquals("새 내용", claim(key, later.plusSeconds(1)).push.title)   // 새 예약은 살아 있다
    }

    @Test
    fun `cancel removes only a pending row`() {
        val pending = unique("p")
        val done = unique("d")
        up(push(pending))
        up(push(done))
        store.finish(done, claim(done).claimToken, ScheduledPushStatus.SENT, null, t0)
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
        listOf(sent1, sent2, skipped).forEach { up(push(it, to = who)) }
        up(push(unique("o"), to = other))
        val claims = store.claimDue(t0, lease, 10_000).filter { it.push.recipientId == who }.associateBy { it.push.key }
        store.finish(sent1, claims.getValue(sent1).claimToken, ScheduledPushStatus.SENT, null, t0.minus(Duration.ofHours(30)))
        store.finish(sent2, claims.getValue(sent2).claimToken, ScheduledPushStatus.SENT, null, t0.minusSeconds(60))
        store.finish(skipped, claims.getValue(skipped).claimToken, ScheduledPushStatus.SKIPPED, SkipReason.DAILY_LIMIT, t0)
        assertEquals(1, store.sentCountSince(who, t0.minus(Duration.ofHours(24))))   // 24시간 안은 하나
        assertEquals(2, store.sentCountSince(who, t0.minus(Duration.ofHours(48))))
        val stats = store.stats()
        assertTrue(stats.sent >= 2 && stats.skipped >= 1 && stats.pending >= 1)

        assertEquals(1, store.purgeFinishedBefore(t0.minus(Duration.ofHours(24))))   // 30시간 전에 끝난 하나
        assertEquals(2, store.deleteByRecipient(who))
        assertEquals(0, store.deleteByRecipient(who))   // 멱등
        assertEquals(0, store.sentCountSince(who, t0.minus(Duration.ofDays(30))))
        assertEquals(1, store.claimDue(t0.plus(lease).plusSeconds(1), lease, 10_000).count { it.push.recipientId == other })   // 남의 예약은 그대로(위에서 집혔으니 임대가 끝나야 다시 집힌다)
    }
}
