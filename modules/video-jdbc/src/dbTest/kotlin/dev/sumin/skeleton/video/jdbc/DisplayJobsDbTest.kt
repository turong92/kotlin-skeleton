package dev.sumin.skeleton.video.jdbc

import dev.sumin.skeleton.video.DisplayJobStore
import dev.sumin.skeleton.video.DisplayState
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

/** 표시본 변환 일 표가 두 방언에서 같은 뜻인지 - 같은 이미지는 한 줄 · 동시에 집어도 한 일꾼만 · 임대 만료와 시도 상한 */
@Import(DbTestcontainers::class)
@SpringBootTest(classes = [VideoJdbcTestApplication::class])
class DisplayJobsDbTest {
    @Autowired lateinit var store: DisplayJobStore
    @Autowired lateinit var jdbc: JdbcTemplate

    private val t0 = Instant.parse("2026-11-04T00:00:00.123456Z")
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)

    @BeforeTest
    fun clean() {
        jdbc.update("delete from video_display_jobs")
    }

    @Test
    fun `the same image is queued once and racing workers never take the same job`() {
        store.enqueue(a, "uploads/7/g/x.gif", t0)
        store.enqueue(a, "uploads/7/g/other.gif", t0.plusSeconds(1))   // 이미 줄에 있다
        assertEquals(1, jdbc.queryForObject("select count(*) from video_display_jobs", Int::class.java))

        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        val taken = (0 until 8).map { i -> pool.submit<Boolean> { start.await(); store.claimNext("w$i", t0.plusSeconds(600), t0) != null } }
        start.countDown()
        val winners = taken.count { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1, winners)
        val job = store.find(a)!!
        assertEquals(DisplayState.RUNNING, job.state)
        assertEquals(1, job.attempts)
        assertEquals("uploads/7/g/x.gif", job.objectKey)
        assertEquals(t0.plusSeconds(600), job.leaseUntil, "마이크로초 시각이 그대로 돌아온다")
    }

    @Test
    fun `claim takes the oldest first and finish and release only work for the lease owner`() {
        store.enqueue(b, "uploads/7/b.gif", t0.plusSeconds(5))
        store.enqueue(a, "uploads/7/a.gif", t0)

        val first = store.claimNext("w1", t0.plusSeconds(600), t0)!!
        assertEquals(a, first.keyHash)
        assertFalse(store.finish(a, "intruder", t0))
        assertFalse(store.release(a, "intruder", true, "X", t0))
        assertTrue(store.extendLease(a, "w1", t0.plusSeconds(1200), t0))
        assertTrue(store.finish(a, "w1", t0))
        assertEquals(DisplayState.DONE, store.find(a)!!.state)
        assertFalse(store.finish(a, "w1", t0), "끝난 일은 다시 끝낼 수 없다")
        assertEquals(b, store.claimNext("w1", t0.plusSeconds(600), t0)!!.keyHash)
        assertNull(store.claimNext("w2", t0.plusSeconds(600), t0))
    }

    @Test
    fun `a released job is retried until the third attempt and then FAILED - expired leases follow the same rule`() {
        store.enqueue(a, "uploads/7/a.gif", t0)
        repeat(2) {
            assertNotNull(store.claimNext("w1", t0.plusSeconds(600), t0))
            assertTrue(store.release(a, "w1", true, "PROCESSING_FAILED", t0))
            assertEquals(DisplayState.QUEUED, store.find(a)!!.state)
        }
        assertNotNull(store.claimNext("w1", t0.plusSeconds(600), t0))
        assertTrue(store.release(a, "w1", true, "PROCESSING_FAILED", t0))
        assertEquals(DisplayState.FAILED, store.find(a)!!.state, "세 번째 시도 뒤에는 다시 주지 않는다")

        store.enqueue(b, "uploads/7/b.gif", t0)
        store.claimNext("w1", t0.plusSeconds(600), t0)
        assertEquals(0, store.expireLeases(t0.plusSeconds(599)))
        assertEquals(1, store.expireLeases(t0.plusSeconds(601)))
        assertEquals(DisplayState.QUEUED, store.find(b)!!.state)
        assertNull(store.find(b)!!.leaseOwner)
    }
}
