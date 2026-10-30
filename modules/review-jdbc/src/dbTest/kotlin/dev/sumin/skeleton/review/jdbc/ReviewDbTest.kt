package dev.sumin.skeleton.review.jdbc

import dev.sumin.skeleton.review.Review
import dev.sumin.skeleton.review.ReviewErrorCode
import dev.sumin.skeleton.review.ReviewEventListener
import dev.sumin.skeleton.review.ReviewException
import dev.sumin.skeleton.review.ReviewFilter
import dev.sumin.skeleton.review.ReviewInput
import dev.sumin.skeleton.review.ReviewPurchases
import dev.sumin.skeleton.review.ReviewReader
import dev.sumin.skeleton.review.ReviewService
import dev.sumin.skeleton.review.ReviewSort
import dev.sumin.skeleton.review.ReviewStatus
import dev.sumin.skeleton.review.ReviewStore
import dev.sumin.skeleton.review.ReviewTarget
import dev.sumin.skeleton.review.erasure.ReviewAccountErasureListener
import dev.sumin.skeleton.common.erasure.AccountTombstone
import dev.sumin.skeleton.common.erasure.ErasureRequest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient

@TestConfiguration(proxyBeanMethods = false)
class FlakyListener {
    /** 켜 두면 공개 이벤트마다 던진다 - 보상 저장소가 죽은 날 */
    @Bean fun flakyListener(): FlakyReviewListener = FlakyReviewListener()
}

class FlakyReviewListener : ReviewEventListener {
    val failing = AtomicBoolean(false)
    override fun onPublished(review: Review, firstTime: Boolean) { if (failing.get()) error("reward store is down") }
}

/**
 * 동시 쓰기 · 고치기 · 지우기에서도 집계가 `PUBLISHED` 줄을 다시 센 값과 같고, 같은 계정의 동시 두 번은 한 줄, 도움돼요 동시 토글은 표 줄 수와 같고,
 * 구매 신호는 리뷰 전/후 어느 쪽이든 `verified` 를 맞추고, 이벤트 고리가 던지면 쓰기가 되돌아가는지 두 방언(postgresTest · mysqlTest)에서 지켜본다.
 */
@Import(DbTestcontainers::class, FlakyListener::class)
@SpringBootTest(classes = [ReviewJdbcTestApplication::class], properties = ["skeleton.review.target-types=product"])
class ReviewDbTest {
    @Autowired lateinit var service: ReviewService
    @Autowired lateinit var reader: ReviewReader
    @Autowired lateinit var purchases: ReviewPurchases
    @Autowired lateinit var store: ReviewStore
    @Autowired lateinit var flaky: FlakyReviewListener
    @Autowired lateinit var jdbc: JdbcClient

    private val seq = UUID.randomUUID().toString().take(8)
    private fun account(n: Int) = "acc_${seq}_$n"
    private fun target(name: String) = ReviewTarget("product", "$name-$seq")

    private fun <T> concurrently(count: Int, block: (Int) -> T): List<T> {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(count)
        val futures = (0 until count).map { i -> pool.submit<T> { start.await(); block(i) } }
        start.countDown()
        return futures.map { it.get(120, TimeUnit.SECONDS) }.also { pool.shutdown() }
    }

    private fun write(account: String, target: ReviewTarget, rating: Int = 5, body: String = "good value for money") =
        service.create(account, target, ReviewInput(rating, body))

    private fun code(block: () -> Unit): ReviewErrorCode = assertFailsWith<ReviewException> { block() }.reviewError

    private fun count(sql: String, vararg args: Any) = jdbc.sql(sql).params(*args).query(Long::class.java).single()

    /** 집계 줄과 `PUBLISHED` 줄을 다시 센 값이 같아야 한다 */
    private fun assertStatsMatchRows(target: ReviewTarget) {
        val rows = jdbc.sql("select rating from reviews where target_type = ? and target_id = ? and status = 'PUBLISHED'").params(target.type, target.id).query { rs, _ -> rs.getInt(1) }.list()
        val stats = store.stats(target)
        assertEquals(rows.size, stats.count, "count")
        assertEquals(rows.sum(), stats.ratingSum, "sum")
        assertEquals((1..5).associateWith { star -> rows.count { it == star } }, stats.distribution, "distribution")
    }

    @Test
    fun `forty threads writing, editing and deleting one target leave the aggregate equal to the published lines`() {
        val t = target("race")
        concurrently(40) { i ->
            val me = account(i)
            val random = Random(i)
            repeat(6) {
                runCatching {
                    when (random.nextInt(4)) {
                        0, 1 -> write(me, t, random.nextInt(1, 6))
                        2 -> store.findByTarget(t, me)?.let { service.update(me, it.id, ReviewInput(rating = random.nextInt(1, 6))) }
                        else -> store.findByTarget(t, me)?.let { service.remove(me, it.id) }
                    }
                }.onFailure { e -> if (e !is ReviewException) throw e }   // 이미 썼다 · 없다 같은 규칙 거절만 허용, 교착 같은 것은 터진다
            }
        }
        assertStatsMatchRows(t)
        assertTrue(store.stats(t).count > 0, "the scenario must leave published lines to compare")
    }

    @Test
    fun `the same account writing at the same time leaves one line, the rest get 409`() {
        val t = target("twice")
        val results = concurrently(8) { runCatching { write(account(1), t) } }
        assertEquals(1, results.count { it.isSuccess })
        results.mapNotNull { it.exceptionOrNull() }.forEach { assertEquals(ReviewErrorCode.ALREADY_WRITTEN, (it as ReviewException).reviewError) }
        assertEquals(1L, count("select count(*) from reviews where target_type = ? and target_id = ?", t.type, t.id))
        assertStatsMatchRows(t)
        assertEquals(1, store.stats(t).count)
    }

    @Test
    fun `a review deleted and written again is the same line and starts without the old helpful votes`() {
        val t = target("revive")
        val first = write(account(1), t, 2)
        service.setHelpful(account(2), first.id, true)
        service.remove(account(1), first.id)
        assertEquals(0, store.stats(t).count)
        val again = write(account(1), t, 5, "changed my mind completely")
        assertEquals(first.id, again.id)
        assertEquals(0, again.helpfulCount)
        assertEquals(0L, count("select count(*) from review_helpful where review_id = ?", first.id))
        assertStatsMatchRows(t)
        assertEquals(1L, count("select count(*) from reviews where target_type = ? and target_id = ?", t.type, t.id))
    }

    @Test
    fun `concurrent helpful toggles end with a count equal to the vote rows`() {
        val t = target("helpful")
        val review = write(account(0), t)
        concurrently(40) { i ->
            val random = Random(i)
            repeat(8) { service.setHelpful(account(1 + i % 12), review.id, random.nextBoolean()) }   // 같은 계정이 여러 스레드에서 누르고 뗀다
        }
        val stored = store.find(review.id)!!
        assertEquals(count("select count(*) from review_helpful where review_id = ?", review.id).toInt(), stored.helpfulCount)
        // 같은 계정이 동시에 여럿 눌러도 한 표
        val other = write(account(100), t)
        concurrently(8) { service.setHelpful(account(200), other.id, true) }
        assertEquals(1, store.find(other.id)!!.helpfulCount)
        assertEquals(1L, count("select count(*) from review_helpful where review_id = ?", other.id))
    }

    @Test
    fun `a purchase signal before the review verifies it at writing, after the review it verifies the line`() {
        val before = target("pbefore")
        purchases.record(account(1), before, "order:${before.id}")
        assertTrue(write(account(1), before).verified)

        val after = target("pafter")
        val review = write(account(2), after)
        assertFalse(review.verified)
        purchases.record(account(2), after, "order:${after.id}")
        purchases.record(account(2), after, "order:${after.id}")   // 같은 ref 두 번 - 한 줄
        assertTrue(store.find(review.id)!!.verified)
        assertEquals(1L, count("select count(*) from review_purchases where ref = ?", "order:${after.id}"))
    }

    @Test
    fun `revoke keeps the badge while another purchase lives and drops it with the last one`() {
        val t = target("revoke")
        val review = write(account(1), t)
        purchases.record(account(1), t, "order:a-$seq")
        purchases.record(account(1), t, "order:b-$seq")
        purchases.revoke("order:a-$seq")
        assertTrue(store.find(review.id)!!.verified)
        purchases.revoke("order:b-$seq")
        assertFalse(store.find(review.id)!!.verified)
        purchases.revoke("order:b-$seq")   // 두 번째 취소는 아무 일도 없다
        purchases.revoke("order:none-$seq")
    }

    @Test
    fun `two purchases revoked at the same time still end unverified`() {
        repeat(5) { round ->
            val t = target("rrace$round")
            val review = write(account(1), t)
            purchases.record(account(1), t, "order:x-$round-$seq")
            purchases.record(account(1), t, "order:y-$round-$seq")
            concurrently(2) { purchases.revoke(if (it == 0) "order:x-$round-$seq" else "order:y-$round-$seq") }
            assertFalse(store.find(review.id)!!.verified, "round $round")
        }
    }

    @Test
    fun `a listener that throws rolls the whole write back`() {
        val t = target("boom")
        flaky.failing.set(true)
        try {
            assertFailsWith<IllegalStateException> { write(account(1), t) }
        } finally {
            flaky.failing.set(false)
        }
        assertNull(store.findByTarget(t, account(1)))
        assertEquals(0, store.stats(t).count)
        assertNotNull(write(account(1), t))   // 되돌아갔으니 다시 쓸 수 있다
    }

    @Test
    fun `others read published lines only and the author also reads the own hidden one`() {
        val t = target("lists")
        val a = write(account(1), t, 5)
        val b = write(account(2), t, 3)
        val c = write(account(3), t, 1)
        jdbc.sql("update reviews set status = 'HIDDEN', moderated_by = 'acc_op', moderation_reason = 'rude' where id = ?").param(b.id).update()
        service.remove(account(3), c.id)
        val forOthers = reader.list(account(9), t, ReviewFilter(), ReviewSort.RECENT, 0, 20)
        assertEquals(listOf(a.id), forOthers.values.map { it.review.id })
        assertEquals(1L, forOthers.total)
        assertEquals(setOf(a.id, b.id), reader.list(account(2), t, ReviewFilter(), ReviewSort.RECENT, 0, 20).values.map { it.review.id }.toSet())
        assertEquals(listOf(a.id), reader.list(null, t, ReviewFilter(), ReviewSort.RATING_HIGH, 0, 20).values.map { it.review.id })
        assertEquals(listOf(b.id), reader.mine(account(2), null, 10).values.map { it.review.id })
    }

    @Test
    fun `sorting, filtering and paging run in SQL`() {
        val t = target("sorts")
        val reviews = (1..5).map { write(account(it), t, it) }
        purchases.record(account(2), t, "order:s-$seq")
        service.setHelpful(account(1), reviews[3].id, true)
        fun ids(sort: ReviewSort, filter: ReviewFilter = ReviewFilter(), offset: Int = 0, limit: Int = 20) = reader.list(null, t, filter, sort, offset, limit).values.map { it.review.id }
        assertEquals(reviews.map { it.id }.reversed(), ids(ReviewSort.RATING_HIGH))
        assertEquals(reviews.map { it.id }, ids(ReviewSort.RATING_LOW))
        assertEquals(reviews[3].id, ids(ReviewSort.HELPFUL).first())
        assertEquals(listOf(reviews[1].id), ids(ReviewSort.RECENT, ReviewFilter(verified = true)))
        assertEquals(listOf(reviews[2].id), ids(ReviewSort.RECENT, ReviewFilter(rating = 3)))
        assertEquals(listOf(reviews[3].id), ids(ReviewSort.RATING_LOW, offset = 3, limit = 1))
        assertEquals(5L, reader.list(null, t, ReviewFilter(), ReviewSort.RECENT, 0, 1).total)
        assertEquals(3.0, store.stats(t).average)
    }

    @Test
    fun `account erasure tombstones the lines, votes and keeps the aggregate, and drops the purchases`() {
        val t = target("erase")
        val review = write(account(1), t, 4)
        service.setHelpful(account(2), review.id, true)
        purchases.record(account(2), t, "order:e-$seq")
        val erasure = ReviewAccountErasureListener(store)
        erasure.erase(ErasureRequest(account(1), AccountTombstone.of(account(1))))
        erasure.erase(ErasureRequest(account(2), AccountTombstone.of(account(2))))
        erasure.erase(ErasureRequest(account(2), AccountTombstone.of(account(2))))   // 두 번째도 같다
        assertEquals(0L, count("select count(*) from reviews where account_id in (?, ?)", account(1), account(2)))
        assertEquals(0L, count("select count(*) from review_helpful where account_id = ?", account(2)))
        assertEquals(0L, count("select count(*) from review_purchases where account_id = ?", account(2)))
        assertEquals(1, store.stats(t).count)
        assertEquals(1, store.find(review.id)!!.helpfulCount)
        val line = reader.list(account(3), t, ReviewFilter(), ReviewSort.RECENT, 0, 20).values.single()
        assertTrue(line.authorDeleted)
        assertNull(line.authorName)
        assertEquals(ReviewStatus.PUBLISHED, line.review.status)
    }

    @Test
    fun `countWritten counts lines including deleted ones`() {
        val t = target("written")
        val r = write(account(1), t)
        service.remove(account(1), r.id)
        write(account(1), target("written2"))
        assertEquals(2, reader.countWritten(account(1)))
    }
}
