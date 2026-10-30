package dev.sumin.skeleton.review

import dev.sumin.skeleton.common.author.AuthorCard
import dev.sumin.skeleton.common.author.AuthorDirectory
import dev.sumin.skeleton.common.id.SortableIds
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Harness(val properties: ReviewProperties = ReviewProperties(targetTypes = listOf("product"))) {
    val store = FakeReviewStore()
    val time = TickingTime()
    val listener = RecordingListener()
    var targets: ReviewTargets? = null
    var policy: ReviewPolicy = object : ReviewPolicy {}
    var directory: AuthorDirectory = AuthorDirectory { ids, _ -> ids.associateWith { AuthorCard("name-$it", "0001") } }
    private val rules = ReviewTargetRules(properties) { targets }
    val service = ReviewService(store, properties, ReviewContentRules(properties), rules, { policy }, { listOf(listener) }, time, SortableIds())
    val purchases = ReviewPurchaseService(store, time, SortableIds())
    val reader = ReviewReader(store, properties, rules, { policy }, { directory })
}

val BASIC = ReviewTarget("product", "basic")

class ReviewServicesTest {
    private var h = Harness()

    @BeforeTest fun fresh() { h = Harness() }

    private fun write(account: String, rating: Int = 5, body: String = "very good indeed", target: ReviewTarget = BASIC) =
        h.service.create(account, target, ReviewInput(rating, body))

    private fun code(block: () -> Unit): ReviewErrorCode = assertFailsWith<ReviewException> { block() }.reviewError

    @Test
    fun `a review is published, counted and announced as first time`() {
        val r = write("acc_1", 4)
        assertEquals(ReviewStatus.PUBLISHED, r.status)
        assertFalse(r.verified)
        assertEquals(TargetStats(1, 4, mapOf(1 to 0, 2 to 0, 3 to 0, 4 to 1, 5 to 0)), h.store.stats(BASIC))
        assertEquals(setOf(r.id to true), h.listener.published)
    }

    @Test
    fun `an unconfigured type or a target the app says does not exist is 404`() {
        assertEquals(ReviewErrorCode.TARGET_NOT_FOUND, code { write("acc_1", target = ReviewTarget("post", "1")) })
        h.targets = ReviewTargets { _, ids -> ids.filter { it == "basic" }.toSet() }
        assertEquals(ReviewErrorCode.TARGET_NOT_FOUND, code { write("acc_1", target = ReviewTarget("product", "ghost")) })
        write("acc_1")
    }

    @Test
    fun `without a ReviewTargets bean only the id shape is checked`() {
        assertEquals(ReviewErrorCode.TARGET_NOT_FOUND, code { write("acc_1", target = ReviewTarget("product", "x".repeat(65))) })
        assertEquals(ReviewErrorCode.TARGET_NOT_FOUND, code { write("acc_1", target = ReviewTarget("product", " ")) })
    }

    @Test
    fun `an empty target-types list accepts nothing`() {
        h = Harness(ReviewProperties())
        assertEquals(ReviewErrorCode.TARGET_NOT_FOUND, code { write("acc_1") })
    }

    @Test
    fun `require-purchase refuses non buyers with 403 and lets buyers in with the verified flag`() {
        h = Harness(ReviewProperties(targetTypes = listOf("product"), requirePurchase = true))
        assertEquals(ReviewErrorCode.PURCHASE_REQUIRED, code { write("acc_1") })
        h.purchases.record("acc_1", BASIC, "order:1")
        assertTrue(write("acc_1").verified)
    }

    @Test
    fun `a second review of the same target by the same account is 409`() {
        write("acc_1")
        assertEquals(ReviewErrorCode.ALREADY_WRITTEN, code { write("acc_1", 1) })
        assertEquals(1, h.store.stats(BASIC).count)
    }

    @Test
    fun `deleting leaves the line and writing again revives the same line without a first-time event`() {
        val first = write("acc_1", 2)
        h.service.remove("acc_1", first.id)
        assertEquals(0, h.store.stats(BASIC).count)
        assertEquals(setOf(first.id), h.listener.removed)
        h.time.advance()
        val again = write("acc_1", 5, "second try is better")
        assertEquals(first.id, again.id)
        assertEquals(ReviewStatus.PUBLISHED, again.status)
        assertEquals(TargetStats(1, 5, mapOf(1 to 0, 2 to 0, 3 to 0, 4 to 0, 5 to 1)), h.store.stats(BASIC))
        assertTrue(first.id to false in h.listener.published)
        assertEquals(1, h.reader.countWritten("acc_1"))
    }

    @Test
    fun `a review deleted after a moderator hid it cannot be written again`() {
        val r = write("acc_1")
        h.store.put(r.copy(status = ReviewStatus.DELETED, moderatedBy = "acc_op", moderationReason = "spam"))
        assertEquals(ReviewErrorCode.LOCKED, code { write("acc_1") })
    }

    @Test
    fun `an edit moves the rating between buckets and shows as edited`() {
        val r = write("acc_1", 5)
        h.time.advance()
        val edited = h.service.update("acc_1", r.id, ReviewPatch(rating = 2, body = "changed my mind"))
        assertEquals(2, edited.rating)
        assertEquals("changed my mind", edited.body)
        assertTrue(edited.updatedAt.isAfter(edited.publishedAt))
        assertEquals(TargetStats(1, 2, mapOf(1 to 0, 2 to 1, 3 to 0, 4 to 0, 5 to 0)), h.store.stats(BASIC))
    }

    @Test
    fun `only the author edits or deletes, a hidden review can only be deleted`() {
        val r = write("acc_1")
        assertEquals(ReviewErrorCode.FORBIDDEN, code { h.service.update("acc_2", r.id, ReviewPatch(rating = 1)) })
        assertEquals(ReviewErrorCode.FORBIDDEN, code { h.service.remove("acc_2", r.id) })
        h.store.put(r.copy(status = ReviewStatus.HIDDEN, moderatedBy = "acc_op", moderationReason = "rude"))
        assertEquals(ReviewErrorCode.FORBIDDEN, code { h.service.update("acc_1", r.id, ReviewPatch(rating = 1)) })
        h.service.remove("acc_1", r.id)
        assertEquals(ReviewErrorCode.NOT_FOUND, code { h.service.remove("acc_1", r.id) })
        // 남의 숨김/지움 줄은 있는지도 알려 주지 않는다
        assertEquals(ReviewErrorCode.NOT_FOUND, code { h.service.update("acc_2", r.id, ReviewPatch(rating = 1)) })
    }

    @Test
    fun `content rules - rating range, length, control characters, photos`() {
        h = Harness(ReviewProperties(targetTypes = listOf("product"), bodyMinLength = 10, bodyMaxLength = 40))
        assertEquals(ReviewErrorCode.CONTENT_INVALID, code { h.service.create("a", BASIC, ReviewInput(0, "long enough body")) })
        assertEquals(ReviewErrorCode.CONTENT_INVALID, code { h.service.create("a", BASIC, ReviewInput(6, "long enough body")) })
        assertEquals(ReviewErrorCode.CONTENT_INVALID, code { h.service.create("a", BASIC, ReviewInput(null, "long enough body")) })
        assertEquals(ReviewErrorCode.CONTENT_INVALID, code { h.service.create("a", BASIC, ReviewInput(5, "short")) })
        assertEquals(ReviewErrorCode.CONTENT_INVALID, code { h.service.create("a", BASIC, ReviewInput(5, "x".repeat(41))) })
        assertEquals(ReviewErrorCode.CONTENT_INVALID, code { h.service.create("a", BASIC, ReviewInput(5, "long enough body", photos = listOf("k"))) })
        val ok = h.service.create("a", BASIC, ReviewInput(5, "  nice\u0000 <b>one</b>‮ item  "))
        assertEquals("nice <b>one</b> item", ok.body)   // 제어문자 · 방향 덮어쓰기 문자만 지운다, HTML 은 그대로
    }

    @Test
    fun `an empty body is fine when the minimum is zero`() {
        assertEquals("", write("acc_1", 3, "").body)
    }

    @Test
    fun `helpful toggles once per account, never on your own review, never on a hidden or deleted one`() {
        val r = write("acc_1")
        assertEquals(ReviewErrorCode.OWN_REVIEW, code { h.service.setHelpful("acc_1", r.id, true) })
        assertEquals(HelpfulState(1, true), h.service.setHelpful("acc_2", r.id, true))
        assertEquals(HelpfulState(1, true), h.service.setHelpful("acc_2", r.id, true))
        assertEquals(HelpfulState(2, true), h.service.setHelpful("acc_3", r.id, true))
        assertEquals(HelpfulState(1, false), h.service.setHelpful("acc_2", r.id, false))
        assertEquals(HelpfulState(1, false), h.service.setHelpful("acc_2", r.id, false))
        h.service.remove("acc_1", r.id)
        assertEquals(ReviewErrorCode.NOT_FOUND, code { h.service.setHelpful("acc_4", r.id, true) })
    }

    @Test
    fun `a purchase signal after the review verifies it, and revoke keeps it while another purchase lives`() {
        val r = write("acc_1")
        assertFalse(h.store.find(r.id)!!.verified)
        h.purchases.record("acc_1", BASIC, "order:1")
        h.purchases.record("acc_1", BASIC, "order:1")   // 같은 ref 는 한 줄
        h.purchases.record("acc_1", BASIC, "order:2")
        assertTrue(h.store.find(r.id)!!.verified)
        h.purchases.revoke("order:1")
        assertTrue(h.store.find(r.id)!!.verified)
        h.purchases.revoke("order:2")
        assertFalse(h.store.find(r.id)!!.verified)
        h.purchases.revoke("order:unknown")
    }

    @Test
    fun `a purchase for another target or account does not verify`() {
        val r = write("acc_1")
        h.purchases.record("acc_1", ReviewTarget("product", "pro"), "order:1")
        h.purchases.record("acc_2", BASIC, "order:2")
        assertFalse(h.store.find(r.id)!!.verified)
    }

    @Test
    fun `a listener that throws fails the write`() {
        val boom = IllegalStateException("reward store is down")
        val failing = Harness()
        val service = ReviewService(failing.store, failing.properties, ReviewContentRules(failing.properties), ReviewTargetRules(failing.properties) { null }, { failing.policy }, {
            listOf(object : ReviewEventListener { override fun onPublished(review: Review, firstTime: Boolean) { throw boom } })
        }, failing.time, SortableIds())
        val thrown = assertFailsWith<IllegalStateException> { service.create("acc_1", BASIC, ReviewInput(5, "body")) }
        assertEquals(boom, thrown)
    }

    @Test
    fun `the policy can refuse writes`() {
        h.policy = object : ReviewPolicy { override fun canWrite(accountId: String, target: ReviewTarget) = accountId != "acc_banned" }
        assertEquals(ReviewErrorCode.FORBIDDEN, code { write("acc_banned") })
    }

    // 읽기

    @Test
    fun `others see published lines only, the author also sees the own hidden line with its reason`() {
        val a = write("acc_1", 5)
        h.time.advance()
        val b = write("acc_2", 3)
        h.time.advance()
        val c = write("acc_3", 1)
        h.store.put(b.copy(status = ReviewStatus.HIDDEN, moderatedBy = "acc_op", moderationReason = "rude words"))
        h.service.remove("acc_3", c.id)
        val forOthers = h.reader.list("acc_9", BASIC, ReviewFilter(), ReviewSort.RECENT, 0, 20)
        assertEquals(listOf(a.id), forOthers.values.map { it.review.id })
        assertEquals(1L, forOthers.total)
        val forAuthor = h.reader.list("acc_2", BASIC, ReviewFilter(), ReviewSort.RECENT, 0, 20)
        assertEquals(listOf(b.id, a.id), forAuthor.values.map { it.review.id })
        assertTrue(forAuthor.values.first().mine)
        assertFalse(h.reader.list(null, BASIC, ReviewFilter(), ReviewSort.RECENT, 0, 20).values.any { it.review.id == b.id })
    }

    @Test
    fun `sorting and filtering`() {
        val a = write("acc_1", 5); h.time.advance()
        val b = write("acc_2", 2); h.time.advance()
        val c = write("acc_3", 4)
        h.purchases.record("acc_2", BASIC, "order:2")
        h.service.setHelpful("acc_1", b.id, true)
        fun ids(sort: ReviewSort, filter: ReviewFilter = ReviewFilter()) = h.reader.list(null, BASIC, filter, sort, 0, 20).values.map { it.review.id }
        assertEquals(listOf(c.id, b.id, a.id), ids(ReviewSort.RECENT))
        assertEquals(listOf(b.id, c.id, a.id), ids(ReviewSort.HELPFUL))
        assertEquals(listOf(a.id, c.id, b.id), ids(ReviewSort.RATING_HIGH))
        assertEquals(listOf(b.id, c.id, a.id), ids(ReviewSort.RATING_LOW))
        assertEquals(listOf(b.id), ids(ReviewSort.RECENT, ReviewFilter(verified = true)))
        assertEquals(listOf(c.id), ids(ReviewSort.RECENT, ReviewFilter(rating = 4)))
        assertEquals(emptyList(), ids(ReviewSort.RECENT, ReviewFilter(photo = true)))
        assertEquals(listOf(c.id), h.reader.list(null, BASIC, ReviewFilter(), ReviewSort.RECENT, 0, 1).values.map { it.review.id })
        assertEquals(listOf(b.id), h.reader.list(null, BASIC, ReviewFilter(), ReviewSort.RECENT, 1, 1).values.map { it.review.id })
    }

    @Test
    fun `helpful flag is per viewer and names come from the directory in one call`() {
        val r = write("acc_1")
        h.service.setHelpful("acc_2", r.id, true)
        var calls = 0
        h.directory = AuthorDirectory { ids, ctx -> calls++; assertEquals("review", ctx.source); assertEquals("product", ctx.scope); ids.associateWith { AuthorCard("n-$it", "1234") } }
        write("acc_3", 3, "another one")
        val lines = h.reader.list("acc_2", BASIC, ReviewFilter(), ReviewSort.RECENT, 0, 20).values
        assertEquals(1, calls)
        assertEquals(setOf(true, false), lines.map { it.helpful }.toSet())
        assertTrue(lines.all { !it.mine })
        assertEquals("n-acc_1", lines.first { it.review.id == r.id }.authorName)
    }

    @Test
    fun `an author that deleted the account shows as deleted without a name and a throwing directory leaves names empty`() {
        val r = write("acc_1")
        h.store.eraseAccount("acc_1", "deleted:abc123")
        val line = h.reader.list("acc_2", BASIC, ReviewFilter(), ReviewSort.RECENT, 0, 20).values.single()
        assertTrue(line.authorDeleted)
        assertNull(line.authorName)
        assertEquals(r.id, line.review.id)
        h.directory = AuthorDirectory { _, _ -> error("directory down") }
        write("acc_5", 3, "another")
        val after = h.reader.list("acc_2", BASIC, ReviewFilter(), ReviewSort.RECENT, 0, 20).values
        assertEquals(2, after.size)
        assertTrue(after.all { it.authorName == null })
    }

    @Test
    fun `summary tells who can write and why not`() {
        h = Harness(ReviewProperties(targetTypes = listOf("product"), requirePurchase = true))
        assertEquals(WriteBlockedReason.SIGN_IN, h.reader.summary(null, BASIC).writeBlockedReason)
        assertEquals(WriteBlockedReason.PURCHASE_REQUIRED, h.reader.summary("acc_1", BASIC).writeBlockedReason)
        h.purchases.record("acc_1", BASIC, "order:1")
        val open = h.reader.summary("acc_1", BASIC)
        assertTrue(open.canWrite)
        assertNull(open.writeBlockedReason)
        val r = write("acc_1", 4)
        val written = h.reader.summary("acc_1", BASIC)
        assertFalse(written.canWrite)
        assertEquals(WriteBlockedReason.ALREADY_WRITTEN, written.writeBlockedReason)
        assertEquals(r.id, written.myReviewId)
        assertEquals(4.0, written.stats.average)
        assertNull(h.reader.summary("acc_2", BASIC).myReviewId)
        h.store.put(r.copy(status = ReviewStatus.DELETED, moderatedBy = "acc_op"))
        assertEquals(WriteBlockedReason.LOCKED, h.reader.summary("acc_1", BASIC).writeBlockedReason)
        assertEquals(ReviewErrorCode.TARGET_NOT_FOUND, code { h.reader.summary("acc_1", ReviewTarget("post", "1")) })
    }

    @Test
    fun `average is a single decimal and the stats lookup covers targets without reviews`() {
        write("acc_1", 5); write("acc_2", 4); write("acc_3", 4)
        val summary = h.reader.of(listOf(BASIC, ReviewTarget("product", "pro")))
        assertEquals(RatingSummary(3, 4.3), summary[BASIC])
        assertEquals(RatingSummary(0, null), summary[ReviewTarget("product", "pro")])
        assertEquals(listOf(RatingSummary(3, 4.3)), h.reader.summaries(listOf(BASIC)).map { it.summary })
    }

    @Test
    fun `my reviews list pages by cursor and include hidden ones but not deleted ones`() {
        val a = write("acc_1", 5, target = ReviewTarget("product", "a")); h.time.advance()
        val b = write("acc_1", 4, target = ReviewTarget("product", "b")); h.time.advance()
        val c = write("acc_1", 3, target = ReviewTarget("product", "c"))
        h.service.remove("acc_1", c.id)
        h.store.put(b.copy(status = ReviewStatus.HIDDEN, moderationReason = "rude"))
        val page = h.reader.mine("acc_1", null, 10)
        assertEquals(listOf(b.id, a.id), page.values.map { it.review.id })
        val first = h.reader.mine("acc_1", null, 1)
        assertTrue(first.hasNext)
        assertEquals(listOf(a.id), h.reader.mine("acc_1", first.values.last().review.id, 1).values.map { it.review.id })
        assertNotEquals(null, first.nextCursor)
    }
}
