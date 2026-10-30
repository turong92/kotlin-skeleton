package dev.sumin.skeleton.review.jdbc

import dev.sumin.skeleton.review.ReviewErrorCode
import dev.sumin.skeleton.review.ReviewException
import dev.sumin.skeleton.review.ReviewInput
import dev.sumin.skeleton.review.ReviewModerationService
import dev.sumin.skeleton.review.ReviewQueue
import dev.sumin.skeleton.review.ReviewReader
import dev.sumin.skeleton.review.ReviewService
import dev.sumin.skeleton.review.ReviewStatus
import dev.sumin.skeleton.review.ReviewStore
import dev.sumin.skeleton.review.ReviewTarget
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient

private fun <T> concurrently(count: Int, block: (Int) -> T): List<T> {
    val start = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(count)
    val futures = (0 until count).map { i -> pool.submit<T> { start.await(); block(i) } }
    start.countDown()
    return futures.map { it.get(120, TimeUnit.SECONDS) }.also { pool.shutdown() }
}

abstract class ReviewModerationDbBase {
    @Autowired lateinit var service: ReviewService
    @Autowired lateinit var moderation: ReviewModerationService
    @Autowired lateinit var reader: ReviewReader
    @Autowired lateinit var store: ReviewStore
    @Autowired lateinit var jdbc: JdbcClient

    val seq = UUID.randomUUID().toString().take(8)
    fun account(n: Int) = "acc_${seq}_$n"
    fun target(name: String) = ReviewTarget("product", "$name-$seq")
    fun write(account: String, target: ReviewTarget, rating: Int = 5, photos: List<String>? = null) = service.create(account, target, ReviewInput(rating, "a fine review body", photos))
    fun count(sql: String, vararg args: Any) = jdbc.sql(sql).params(*args).query(Long::class.java).single()

    fun assertStatsMatchRows(target: ReviewTarget) {
        val rows = jdbc.sql("select rating from reviews where target_type = ? and target_id = ? and status = 'PUBLISHED'").params(target.type, target.id).query { rs, _ -> rs.getInt(1) }.list()
        val stats = store.stats(target)
        assertEquals(rows.size, stats.count, "count")
        assertEquals(rows.sum(), stats.ratingSum, "sum")
        assertEquals((1..5).associateWith { star -> rows.count { it == star } }, stats.distribution, "distribution")
    }
}

/** 숨기기 · 되살리기가 쓰기 · 고치기 · 지우기와 섞여도 집계가 `PUBLISHED` 줄을 다시 센 값이고, 신고 · 큐 · 답글 · 사진이 두 방언에서 같다 */
@Import(DbTestcontainers::class)
@SpringBootTest(classes = [ReviewJdbcTestApplication::class], properties = ["skeleton.review.target-types=product", "skeleton.review.max-photos=3"])
class ReviewModerationDbTest : ReviewModerationDbBase() {
    @Test
    fun `forty threads writing, editing, deleting, hiding and restoring leave the aggregate equal to the published lines`() {
        val t = target("mix")
        concurrently(40) { i ->
            val me = account(i)
            val random = Random(i)
            repeat(8) {
                runCatching {
                    when (random.nextInt(6)) {
                        0, 1 -> write(me, t, random.nextInt(1, 6))
                        2 -> store.findByTarget(t, me)?.let { service.update(me, it.id, ReviewInput(rating = random.nextInt(1, 6))) }
                        3 -> store.findByTarget(t, me)?.let { service.remove(me, it.id) }
                        4 -> store.findByTarget(t, account(random.nextInt(40)))?.let { moderation.hide("acc_op", it.id, "rude") }   // 운영자가 아무나 숨긴다
                        else -> store.findByTarget(t, account(random.nextInt(40)))?.let { moderation.restore(it.id) }
                    }
                }.onFailure { e -> if (e !is ReviewException) throw e }
            }
        }
        assertStatsMatchRows(t)
    }

    @Test
    fun `the same account reporting at the same time counts once`() {
        val t = target("reports")
        val r = write(account(0), t)
        concurrently(8) { service.report(account(1), r.id, "SPAM", "ad") }
        assertEquals(1, store.find(r.id)!!.reportCount)
        assertEquals(1L, count("select count(*) from review_reports where review_id = ?", r.id))
        concurrently(10) { service.report(account(10 + it), r.id, if (it % 2 == 0) "SPAM" else "ABUSE", null) }
        assertEquals(11, store.find(r.id)!!.reportCount)
        assertEquals(count("select count(*) from review_reports where review_id = ?", r.id).toInt(), store.find(r.id)!!.reportCount)
        assertEquals(mapOf("SPAM" to 6, "ABUSE" to 5), reader.adminPage(ReviewQueue.REPORTED, t.type, null, 50).rows.first { it.review.id == r.id }.reportReasons)
    }

    @Test
    fun `the queue holds unreviewed reports of published reviews and hide, dismiss and restore move it`() {
        val t = target("queue")
        val a = write(account(1), t, 5)
        val b = write(account(2), t, 3)
        service.report(account(5), a.id, "SPAM", null)
        service.report(account(5), b.id, "OTHER", null)
        fun ids(q: ReviewQueue) = reader.adminPage(q, t.type, null, 100).rows.map { it.review.id }.filter { it in setOf(a.id, b.id) }
        assertEquals(setOf(a.id, b.id), ids(ReviewQueue.REPORTED).toSet())
        moderation.hide("acc_op", a.id, "spam ad")
        assertEquals(listOf(b.id), ids(ReviewQueue.REPORTED))
        assertEquals(listOf(a.id), ids(ReviewQueue.HIDDEN))
        Thread.sleep(5)
        moderation.dismissReports(b.id)
        assertEquals(emptyList(), ids(ReviewQueue.REPORTED))
        Thread.sleep(5)
        service.report(account(6), b.id, "SPAM", null)   // 새 신고가 큐에 다시 올린다
        assertEquals(listOf(b.id), ids(ReviewQueue.REPORTED))
        assertEquals(setOf(a.id, b.id), ids(ReviewQueue.ALL).toSet())
        assertEquals(1, store.stats(t).count)
        moderation.restore(a.id)
        assertEquals(2, store.stats(t).count)
        assertStatsMatchRows(t)
        service.remove(account(2), b.id)
        assertEquals(emptyList(), ids(ReviewQueue.REPORTED))   // 지운 줄은 큐에 없다
    }

    @Test
    fun `hidden then deleted stays locked until restored, and others never read the hidden line`() {
        val t = target("locked")
        val r = write(account(1), t)
        moderation.hide("acc_op", r.id, "rude")
        assertEquals(emptyList(), reader.list(account(9), t, dev.sumin.skeleton.review.ReviewFilter(), dev.sumin.skeleton.review.ReviewSort.RECENT, 0, 10).values)
        assertEquals(listOf(r.id), reader.list(account(1), t, dev.sumin.skeleton.review.ReviewFilter(), dev.sumin.skeleton.review.ReviewSort.RECENT, 0, 10).values.map { it.review.id })
        service.remove(account(1), r.id)
        assertEquals(ReviewErrorCode.LOCKED, assertFailsWith<ReviewException> { write(account(1), t) }.reviewError)
        moderation.restore(r.id)   // 잠금만 풀린다 - 작성자가 지운 줄은 다시 공개되지 않는다
        assertEquals(ReviewStatus.DELETED, store.find(r.id)!!.status)
        assertNull(store.find(r.id)!!.moderatedBy)
        assertEquals(0, store.stats(t).count)
        assertEquals(r.id, write(account(1), t).id)
        assertEquals(1, store.stats(t).count)
        assertStatsMatchRows(t)
    }

    @Test
    fun `a reply is one row edited in place and a rewritten review starts without reply, reports and photos`() {
        val t = target("reply")
        val r = write(account(1), t, 4, listOf("reviews/a.jpg", "reviews/b.jpg"))
        moderation.reply("acc_op", r.id, "first answer")
        Thread.sleep(5)
        concurrently(6) { moderation.reply("acc_op", r.id, "answer $it") }
        assertEquals(1L, count("select count(*) from review_replies where review_id = ?", r.id))
        service.report(account(2), r.id, "SPAM", null)
        assertEquals("reviews/b.jpg", store.photoKey(r.id, 1))
        service.remove(account(1), r.id)
        val again = write(account(1), t, 5)
        assertEquals(r.id, again.id)
        assertEquals(0L, count("select count(*) from review_replies where review_id = ?", r.id))
        assertEquals(0L, count("select count(*) from review_reports where review_id = ?", r.id))
        assertEquals(0L, count("select count(*) from review_photos where review_id = ?", r.id))
        assertEquals(0, store.find(r.id)!!.photoCount)
        assertEquals(0, store.find(r.id)!!.reportCount)
    }

    @Test
    fun `photos are replaced as a whole and counted`() {
        val t = target("photos")
        val r = write(account(1), t, 5, listOf("reviews/a.jpg", "reviews/b.jpg"))
        assertEquals(2, store.find(r.id)!!.photoCount)
        service.update(account(1), r.id, ReviewInput(photos = listOf("reviews/c.jpg")))
        assertEquals(1, store.find(r.id)!!.photoCount)
        assertEquals("reviews/c.jpg", store.photoKey(r.id, 0))
        assertNull(store.photoKey(r.id, 1))
        val own = reader.one(account(1), r.id)!!
        assertEquals(listOf("reviews/c.jpg"), own.photoKeys)
        assertNull(reader.one(account(2), r.id)!!.photoKeys)
        service.update(account(1), r.id, ReviewInput(photos = emptyList()))
        assertEquals(0L, count("select count(*) from review_photos where review_id = ?", r.id))
    }
}

/** 자동 가림을 켠 설정 - 동시에 신고가 몰려도 N 번째에서 한 번 숨기고 집계가 맞는다 */
@Import(DbTestcontainers::class)
@SpringBootTest(classes = [ReviewJdbcTestApplication::class], properties = ["skeleton.review.target-types=product", "skeleton.review.report.auto-hide-at=5"])
class ReviewAutoHideDbTest : ReviewModerationDbBase() {
    @Test
    fun `concurrent reports hide the review once and take it out of the aggregate once`() {
        val t = target("auto")
        val r = write(account(0), t, 4)
        write(account(100), t, 2)
        concurrently(15) { runCatching { service.report(account(1 + it), r.id, "SPAM", null) }.onFailure { e -> if (e !is ReviewException) throw e } }
        val hidden = store.find(r.id)!!
        assertEquals(ReviewStatus.HIDDEN, hidden.status)
        assertEquals("AUTO_REPORTS", hidden.moderationReason)
        assertTrue(hidden.reportCount >= 5)
        assertEquals(1, store.stats(t).count)
        assertStatsMatchRows(t)
    }
}
