package dev.sumin.skeleton.review

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReviewModerationTest {
    private var h = Harness()

    @BeforeTest fun fresh() { h = Harness() }

    private fun write(account: String, rating: Int = 5, target: ReviewTarget = BASIC) = h.service.create(account, target, ReviewInput(rating, "a perfectly fine review"))

    private fun code(block: () -> Unit): ReviewErrorCode = assertFailsWith<ReviewException> { block() }.reviewError

    private fun queue(q: ReviewQueue = ReviewQueue.REPORTED) = h.reader.adminPage(q, null, null, 50).rows.map { it.review.id }

    // 신고

    @Test
    fun `a report counts once per account, with the reason codes by kind, and puts the review in the queue`() {
        val r = write("acc_1")
        h.service.report("acc_2", r.id, "spam", "looks like an ad")
        h.service.report("acc_2", r.id, "ABUSE", null)   // 두 번째는 같은 응답으로 끝나고 세지 않는다
        h.service.report("acc_3", r.id, "OTHER", null)
        assertEquals(2, h.store.find(r.id)!!.reportCount)
        assertEquals(listOf(r.id), queue())
        val row = h.reader.adminPage(ReviewQueue.REPORTED, null, null, 10).rows.single()
        assertEquals(mapOf("SPAM" to 1, "OTHER" to 1), row.reportReasons)
    }

    @Test
    fun `reports refuse your own review, an unknown reason, and a review nobody can see`() {
        val r = write("acc_1")
        assertEquals(ReviewErrorCode.OWN_REVIEW, code { h.service.report("acc_1", r.id, "SPAM", null) })
        assertEquals(ReviewErrorCode.CONTENT_INVALID, code { h.service.report("acc_2", r.id, "NONSENSE", null) })
        assertEquals(ReviewErrorCode.CONTENT_INVALID, code { h.service.report("acc_2", r.id, null, null) })
        assertEquals(ReviewErrorCode.CONTENT_INVALID, code { h.service.report("acc_2", r.id, "SPAM", "x".repeat(501)) })
        h.service.remove("acc_1", r.id)
        assertEquals(ReviewErrorCode.NOT_FOUND, code { h.service.report("acc_2", r.id, "SPAM", null) })
        assertEquals(ReviewErrorCode.NOT_FOUND, code { h.service.report("acc_2", "rvw_missing", "SPAM", null) })
    }

    @Test
    fun `auto hide is off by default and hides at the Nth report when set`() {
        val off = write("acc_1")
        (2..6).forEach { h.service.report("acc_$it", off.id, "SPAM", null) }
        assertEquals(ReviewStatus.PUBLISHED, h.store.find(off.id)!!.status)

        h = Harness(ReviewProperties(targetTypes = listOf("product"), report = ReviewProperties.Report(autoHideAt = 3)))
        val r = write("acc_1", 4)
        h.service.report("acc_2", r.id, "SPAM", null)
        h.service.report("acc_3", r.id, "SPAM", null)
        assertEquals(ReviewStatus.PUBLISHED, h.store.find(r.id)!!.status)
        h.service.report("acc_4", r.id, "SPAM", null)
        val hidden = h.store.find(r.id)!!
        assertEquals(ReviewStatus.HIDDEN, hidden.status)
        assertEquals("AUTO_REPORTS", hidden.moderationReason)
        assertEquals(0, h.store.stats(BASIC).count)
        assertEquals(setOf(r.id), h.listener.removed)
        assertEquals(listOf(Triple("acc_1", r.id, "AUTO_REPORTS")), h.notifier.hidden)
        assertEquals(emptyList(), queue())   // 숨기면 큐에서 빠진다
    }

    // 숨김 · 되살리기 · 문제없음

    @Test
    fun `hiding takes the review out of the aggregate and the public list, and tells the author`() {
        val r = write("acc_1", 4); write("acc_2", 2)
        h.service.report("acc_3", r.id, "ABUSE", null)
        h.moderation.hide("acc_op", r.id, "  rude language ")
        val hidden = h.store.find(r.id)!!
        assertEquals(ReviewStatus.HIDDEN, hidden.status)
        assertEquals("rude language", hidden.moderationReason)
        assertEquals("acc_op", hidden.moderatedBy)
        assertEquals(TargetStats(1, 2, mapOf(1 to 0, 2 to 1, 3 to 0, 4 to 0, 5 to 0)), h.store.stats(BASIC))
        assertEquals(emptyList(), queue())
        assertEquals(listOf(r.id), queue(ReviewQueue.HIDDEN))
        assertEquals(setOf(r.id), h.listener.removed)
        assertEquals(listOf(Triple("acc_1", r.id, "rude language")), h.notifier.hidden)
        h.moderation.hide("acc_op", r.id, "again")   // 이미 숨겼으면 그대로
        assertEquals(1, h.store.stats(BASIC).count)
        assertEquals(1, h.notifier.hidden.size)
    }

    @Test
    fun `a hide needs a reason and a visible review`() {
        val r = write("acc_1")
        assertEquals(ReviewErrorCode.CONTENT_INVALID, code { h.moderation.hide("acc_op", r.id, "  ") })
        assertEquals(ReviewErrorCode.CONTENT_INVALID, code { h.moderation.hide("acc_op", r.id, null) })
        h.service.remove("acc_1", r.id)
        assertEquals(ReviewErrorCode.NOT_FOUND, code { h.moderation.hide("acc_op", r.id, "late") })
    }

    @Test
    fun `restoring puts the review back in the aggregate and clears the hidden mark`() {
        val r = write("acc_1", 3)
        h.moderation.hide("acc_op", r.id, "oops")
        h.moderation.restore(r.id)
        val back = h.store.find(r.id)!!
        assertEquals(ReviewStatus.PUBLISHED, back.status)
        assertNull(back.moderatedBy)
        assertNull(back.moderationReason)
        assertEquals(1, h.store.stats(BASIC).count)
        assertTrue(r.id to false in h.listener.published)
        h.moderation.restore(r.id)   // 이미 공개면 그대로
        assertEquals(1, h.store.stats(BASIC).count)
    }

    @Test
    fun `a review the author deleted after a hide cannot be rewritten until a moderator unlocks it, and is never made public again by the moderator`() {
        write("acc_9", 3)
        val r = write("acc_1", 5)
        h.moderation.hide("acc_op", r.id, "spam")
        h.service.remove("acc_1", r.id)
        assertEquals(ReviewErrorCode.LOCKED, code { write("acc_1") })
        assertEquals(WriteBlockedReason.LOCKED, h.reader.summary("acc_1", BASIC).writeBlockedReason)
        assertEquals(listOf(r.id), queue(ReviewQueue.HIDDEN))   // 운영자가 풀 수 있게 큐에 남는다
        h.moderation.restore(r.id)   // 운영자 되살리기: 잠금만 풀고 공개하지 않는다
        val after = h.store.find(r.id)!!
        assertEquals(ReviewStatus.DELETED, after.status)
        assertNull(after.moderatedBy)
        assertEquals(1, h.store.stats(BASIC).count)
        assertEquals(1, h.reader.list("acc_2", BASIC, ReviewFilter(), ReviewSort.RECENT, 0, 10).total)
        assertEquals(1, h.reader.summary("acc_2", BASIC).stats.count)
        assertEquals(emptyList(), h.reader.mine("acc_1", null, 10).values)
        assertEquals(emptyList(), queue(ReviewQueue.HIDDEN))
        assertEquals(emptyList(), queue(ReviewQueue.ALL).filter { it == r.id })
        assertFalse(r.id to false in h.listener.published)
        assertEquals(r.id, write("acc_1").id)   // 풀린 뒤에는 작성자가 직접 다시 쓸 수 있다
    }

    @Test
    fun `a hidden review can be deleted but not edited, and a restored one is editable again`() {
        val r = write("acc_1")
        h.moderation.hide("acc_op", r.id, "rude")
        assertEquals(ReviewErrorCode.FORBIDDEN, code { h.service.update("acc_1", r.id, ReviewPatch(rating = 1)) })
        h.moderation.restore(r.id)
        assertEquals(1, h.service.update("acc_1", r.id, ReviewPatch(rating = 1)).rating)
    }

    @Test
    fun `dismissing clears the queue entry until a new report arrives`() {
        val r = write("acc_1")
        h.service.report("acc_2", r.id, "SPAM", null)
        h.time.advance()
        h.moderation.dismissReports(r.id)
        assertEquals(emptyList(), queue())
        assertEquals(ReviewStatus.PUBLISHED, h.store.find(r.id)!!.status)
        h.time.advance()
        h.service.report("acc_3", r.id, "SPAM", null)
        assertEquals(listOf(r.id), queue())
    }

    // 답글

    @Test
    fun `one reply per review, edited in place, shown on the public line, removable`() {
        val r = write("acc_1")
        h.moderation.reply("acc_op", r.id, " thanks for the feedback ")
        h.time.advance()
        h.moderation.reply("acc_op", r.id, "updated answer")
        val reply = h.reader.list("acc_2", BASIC, ReviewFilter(), ReviewSort.RECENT, 0, 10).values.single().reply!!
        assertEquals("updated answer", reply.body)
        assertTrue(reply.updatedAt.isAfter(reply.createdAt))
        assertEquals(listOf("acc_1" to r.id, "acc_1" to r.id), h.notifier.replied)
        h.moderation.removeReply(r.id)
        assertNull(h.reader.list("acc_2", BASIC, ReviewFilter(), ReviewSort.RECENT, 0, 10).values.single().reply)
        assertEquals(ReviewErrorCode.CONTENT_INVALID, code { h.moderation.reply("acc_op", r.id, " ") })
        assertEquals(ReviewErrorCode.CONTENT_INVALID, code { h.moderation.reply("acc_op", r.id, "x".repeat(2001)) })
    }

    @Test
    fun `a reply needs a published review and a rewritten review starts without the old reply`() {
        val r = write("acc_1")
        h.moderation.reply("acc_op", r.id, "answer")
        h.service.remove("acc_1", r.id)
        assertEquals(ReviewErrorCode.NOT_FOUND, code { h.moderation.reply("acc_op", r.id, "late") })
        write("acc_1")
        assertNull(h.store.repliesOf(listOf(r.id))[r.id])
    }

    // 사진

    @Test
    fun `photos are stored as keys within the limit, replaced on edit and counted`() {
        h = Harness(ReviewProperties(targetTypes = listOf("product"), maxPhotos = 2))
        h.policy = object : ReviewPolicy { override fun canAttach(accountId: String, keys: List<String>) = true }
        val r = h.service.create("acc_1", BASIC, ReviewInput(5, "with pictures", listOf(" a.jpg ", "b.jpg", "a.jpg")))
        assertEquals(2, h.store.find(r.id)!!.photoCount)
        assertEquals("a.jpg", h.store.photoKey(r.id, 0))
        assertEquals(listOf(true), h.reader.list(null, BASIC, ReviewFilter(photo = true), ReviewSort.RECENT, 0, 10).values.map { it.review.photoCount == 2 })
        assertEquals(ReviewErrorCode.CONTENT_INVALID, code { h.service.update("acc_1", r.id, ReviewPatch(photos = listOf("1", "2", "3"))) })
        h.service.update("acc_1", r.id, ReviewPatch(body = "body only change"))
        assertEquals(2, h.store.find(r.id)!!.photoCount)   // 사진 칸을 안 만지면 그대로
        h.service.update("acc_1", r.id, ReviewPatch(photos = listOf("c.jpg")))
        assertEquals(listOf("c.jpg"), listOf(h.store.photoKey(r.id, 0)))
        assertNull(h.store.photoKey(r.id, 1))
        h.service.update("acc_1", r.id, ReviewPatch(photos = emptyList()))
        assertEquals(0, h.store.find(r.id)!!.photoCount)
    }

    @Test
    fun `without a policy of the app, photos are refused by default - nobody can attach a key that is not provably their own`() {
        h = Harness(ReviewProperties(targetTypes = listOf("product"), maxPhotos = 2))
        assertEquals(ReviewErrorCode.FORBIDDEN, code { h.service.create("acc_1", BASIC, ReviewInput(5, "x", listOf("uploads/someone-elses-note-attachment.jpg"))) })
        val r = h.service.create("acc_1", BASIC, ReviewInput(5, "no photo needed"))
        assertEquals(ReviewErrorCode.FORBIDDEN, code { h.service.update("acc_1", r.id, ReviewPatch(photos = listOf("uploads/other.jpg"))) })
        assertEquals(0, h.store.find(r.id)!!.photoCount)
    }

    @Test
    fun `a key holding the author's account id is refused, and the policy can refuse attachments`() {
        h = Harness(ReviewProperties(targetTypes = listOf("product"), maxPhotos = 2))
        assertEquals(ReviewErrorCode.CONTENT_INVALID, code { h.service.create("acc_1", BASIC, ReviewInput(5, "x", listOf("uploads/acc_1/a.jpg"))) })
        h.policy = object : ReviewPolicy { override fun canAttach(accountId: String, keys: List<String>) = keys.all { it.startsWith("reviews/") } }
        assertEquals(ReviewErrorCode.FORBIDDEN, code { h.service.create("acc_1", BASIC, ReviewInput(5, "x", listOf("elsewhere/a.jpg"))) })
        assertNotNull(h.service.create("acc_1", BASIC, ReviewInput(5, "x", listOf("reviews/a.jpg"))))
    }

    @Test
    fun `photo keys are readable only by people who can see the review`() {
        h = Harness(ReviewProperties(targetTypes = listOf("product"), maxPhotos = 2))
        h.policy = object : ReviewPolicy { override fun canAttach(accountId: String, keys: List<String>) = true }
        val r = h.service.create("acc_1", BASIC, ReviewInput(5, "x", listOf("reviews/a.jpg")))
        assertEquals("reviews/a.jpg", h.reader.photoKey("acc_2", false, r.id, 0))
        assertNull(h.reader.photoKey("acc_2", false, r.id, 1))
        h.moderation.hide("acc_op", r.id, "rude")
        assertNull(h.reader.photoKey("acc_2", false, r.id, 0))
        assertEquals("reviews/a.jpg", h.reader.photoKey("acc_1", false, r.id, 0))
        assertEquals("reviews/a.jpg", h.reader.photoKey("acc_op", true, r.id, 0))
        h.service.remove("acc_1", r.id)
        assertNull(h.reader.photoKey("acc_1", true, r.id, 0))
        assertFalse(h.reader.photoKey("acc_9", false, "rvw_none", 0) != null)
    }
}
