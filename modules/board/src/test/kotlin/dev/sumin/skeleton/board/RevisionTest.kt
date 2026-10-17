package dev.sumin.skeleton.board

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 고친 판(스펙 §3.5 · §10): 대기 중 공개본만 보이고, 승인 · 거절 · 버림 · 덮어쓰기가 깨지지 않는다 */
class RevisionTest {
    private val events = mutableListOf<BoardEvent>()
    private val pending = mutableListOf<BoardCommentNotice>()
    private val notifier = object : BoardNotifier {
        override fun commentCreated(notice: BoardCommentNotice) = Unit
        override fun commentPending(notice: BoardCommentNotice) { pending += notice }
    }
    private val settings = BoardSettings(
        guestWrite = GuestWrite.ALL, postApproval = ApprovalMode.ALL, commentApproval = ApprovalMode.ALL, ownerModeratesComments = true,
    )
    private val h = ServiceHarness(
        props = BoardProperties(guest = BoardProperties.Guest(enabled = true)), settings = settings, notifier = notifier,
        listeners = listOf(BoardEventListener { events += it }),
    )
    private val third = BoardCaller("acc_third")
    private var addresses = 0
    private fun ctx(secret: String? = "pass1234") = GuestContext(secret, "ip-${addresses++}", "10.0.0.1")

    /** `other` 가 쓴 글이 운영자 승인으로 공개된 상태 */
    private fun publishedPost(attachments: List<String>? = null): Post {
        val p = h.postService.create(other, "general", CreatePost("제목", "본문", attachments, null)).post
        return h.postService.moderate(moderator, "general", p.id, PostStatus.PUBLISHED, null).post
    }

    private fun edit(post: Post, title: String? = "새 제목", body: String? = "새 본문", attachments: List<String>? = null) =
        h.postService.update(other, "general", post.id, UpdatePost(title, body, attachments, null))

    @Test
    fun `an edit of a published post waits as a revision and everyone but the author and moderators see only the public text`() {
        val post = publishedPost(listOf("k/old.png"))
        events.clear()
        val view = edit(post, attachments = listOf("k/new.png"))
        assertEquals("제목", view.post.title, "the public row is untouched")
        assertEquals("새 제목", view.pendingRevision!!.title)
        assertEquals(post.updatedAt, h.store.posts.getValue(post.id).updatedAt)
        val submitted = events.single() as RevisionSubmitted
        assertFalse(submitted.replaced)

        for (viewer in listOf(null, third)) {
            val seen = h.postService.get(viewer, "general", post.id)
            assertNull(seen.pendingRevision, "no sign of a revision for viewer=$viewer")
            assertEquals(listOf("k/old.png"), seen.post.attachments, "the new picture is not in the public response")
        }
        assertEquals("새 본문", h.postService.get(other, "general", post.id).pendingRevision!!.body)
        assertEquals(listOf("k/new.png"), h.postService.get(moderator, "general", post.id).pendingRevision!!.attachments)
    }

    @Test
    fun `editing again while waiting overwrites the one revision`() {
        val post = publishedPost()
        edit(post, body = "첫 판")
        events.clear()
        edit(post, title = null, body = "둘째 판")
        assertEquals(1, h.store.revisions.size)
        val rev = h.postService.get(other, "general", post.id).pendingRevision!!
        assertEquals("둘째 판", rev.body)
        assertEquals("새 제목", rev.title, "a field the second edit left out keeps the first revision's value")
        assertTrue((events.single() as RevisionSubmitted).replaced)
    }

    @Test
    fun `approving moves the revision onto the post, deletes it, and sends Updated plus Decided`() {
        val post = publishedPost(listOf("k/old.png"))
        edit(post, attachments = listOf("k/new.png"))
        events.clear()
        val view = h.postService.decideRevision(moderator, "general", post.id, RevisionDecision.APPROVE, h.v(RevisionTarget.POST, post.id))
        assertEquals("새 제목", view.post.title)
        assertEquals("새 본문", view.post.body)
        assertEquals(listOf("k/new.png"), view.post.attachments)
        assertNull(view.pendingRevision)
        assertTrue(h.store.revisions.isEmpty())
        assertEquals(listOf("PostUpdated", "RevisionDecided"), events.map { it::class.simpleName })
        assertEquals(RevisionOutcome.APPROVED, (events.last() as RevisionDecided).outcome)
    }

    @Test
    fun `rejecting drops only the revision`() {
        val post = publishedPost()
        edit(post)
        events.clear()
        val view = h.postService.decideRevision(moderator, "general", post.id, RevisionDecision.REJECT, h.v(RevisionTarget.POST, post.id))
        assertEquals("제목", view.post.title)
        assertEquals("본문", h.store.posts.getValue(post.id).body)
        assertTrue(h.store.revisions.isEmpty())
        assertEquals(RevisionOutcome.REJECTED, (events.single() as RevisionDecided).outcome)
    }

    @Test
    fun `hiding or deleting the post discards its revision so it can never bring the post back`() {
        val post = publishedPost()
        edit(post)
        events.clear()
        h.postService.moderate(moderator, "general", post.id, PostStatus.HIDDEN, null)
        assertTrue(h.store.revisions.isEmpty())
        assertEquals(RevisionOutcome.DISCARDED, events.filterIsInstance<RevisionDecided>().single().outcome)
        assertEquals(BoardErrorCode.POST_NOT_FOUND, h.failure { h.postService.decideRevision(moderator, "general", post.id, RevisionDecision.APPROVE, h.v(RevisionTarget.POST, post.id)) })
        assertEquals(PostStatus.HIDDEN, h.store.posts.getValue(post.id).status)

        h.postService.moderate(moderator, "general", post.id, PostStatus.PUBLISHED, null)
        edit(post)
        h.postService.delete(other, "general", post.id)
        assertTrue(h.store.revisions.isEmpty())
    }

    @Test
    fun `a moderator's own edit wins over a waiting revision, and only moderators decide`() {
        val post = publishedPost()
        edit(post)
        assertEquals(BoardErrorCode.FORBIDDEN, h.failure { h.postService.decideRevision(other, "general", post.id, RevisionDecision.APPROVE, h.v(RevisionTarget.POST, post.id)) })
        assertEquals(1, h.store.revisions.size)
        events.clear()
        h.postService.update(moderator, "general", post.id, UpdatePost("운영자 제목", null, null, null))
        assertEquals("운영자 제목", h.store.posts.getValue(post.id).title)
        assertTrue(h.store.revisions.isEmpty())
        assertEquals(RevisionOutcome.DISCARDED, events.filterIsInstance<RevisionDecided>().single().outcome)
        assertEquals(BoardErrorCode.POST_NOT_FOUND, h.failure { h.postService.decideRevision(moderator, "general", post.id, RevisionDecision.REJECT, h.v(RevisionTarget.POST, post.id)) })
    }

    @Test
    fun `the pending edit list is for moderators and carries the public summary next to the revision`() {
        val post = publishedPost()
        edit(post)
        val page = h.postService.list(moderator, "general", PostListRequest(0, 20, revision = true))
        assertEquals(listOf(post.id), page.values.map { it.item.id })
        assertEquals("제목", page.values.single().item.title)
        assertEquals("새 제목", page.values.single().pendingRevision!!.title)
        assertEquals(BoardErrorCode.FORBIDDEN, h.failure { h.postService.list(other, "general", PostListRequest(0, 20, revision = true)) })
    }

    // ---- 댓글 ----

    private fun publishedComment(postId: Long): Comment {
        val c = h.commentService.create(third, "general", postId, null, "공개 댓글").comment
        assertEquals(CommentStatus.PENDING, c.status)
        return h.commentService.moderate(moderator, "general", postId, c.id, CommentStatus.PUBLISHED).comment
    }

    @Test
    fun `a published comment's edit waits, the managing owner is told once, and only managers see it`() {
        val post = publishedPost().id
        val c = publishedComment(post)
        pending.clear()
        h.commentService.update(third, "general", post, c.id, "고친 댓글")
        assertEquals("공개 댓글", h.store.comments.getValue(c.id).body)
        assertEquals(1, pending.size)
        assertTrue(pending.single().revision)
        assertEquals("acc_other", pending.single().recipientId)   // 글 주인은 이 글을 쓴 `other`

        fun revisionSeenBy(viewer: BoardCaller?) = h.commentService.list(viewer, "general", post, 0, 20, CommentSort.OLDEST).values.single().pendingRevision
        assertNull(revisionSeenBy(null))
        assertNull(revisionSeenBy(owner), "a stranger sees no sign of it")
        assertEquals("고친 댓글", revisionSeenBy(third)!!.body)
        assertEquals("고친 댓글", revisionSeenBy(other)!!.body)
        assertEquals("고친 댓글", revisionSeenBy(moderator)!!.body)
        assertEquals("공개 댓글", h.commentService.list(owner, "general", post, 0, 20, CommentSort.OLDEST).values.single().body)
    }

    @Test
    fun `a post owner decides revisions only on their own post and approval publishes the text`() {
        val mine = publishedPost().id
        val c = publishedComment(mine)
        h.commentService.update(third, "general", mine, c.id, "고친 댓글")

        val strangersPost = h.postService.create(owner, "general", CreatePost("남의 글", "본문", null, null)).post.id
        h.postService.moderate(moderator, "general", strangersPost, PostStatus.PUBLISHED, null)
        assertEquals(BoardErrorCode.FORBIDDEN, h.failure { h.commentService.decideRevision(owner, "general", mine, c.id, RevisionDecision.APPROVE, h.v(RevisionTarget.COMMENT, c.id)) })

        events.clear()
        val view = h.commentService.decideRevision(other, "general", mine, c.id, RevisionDecision.APPROVE, h.v(RevisionTarget.COMMENT, c.id))
        assertEquals("고친 댓글", view.body)
        assertNull(view.pendingRevision)
        assertEquals(listOf("CommentUpdated", "RevisionDecided"), events.map { it::class.simpleName })
        assertEquals(BoardErrorCode.COMMENT_NOT_FOUND, h.failure { h.commentService.decideRevision(other, "general", mine, c.id, RevisionDecision.APPROVE, h.v(RevisionTarget.COMMENT, c.id)) })
    }

    @Test
    fun `hiding a comment discards its revision and a manager lists the waiting edits oldest first`() {
        val post = publishedPost().id
        val a = publishedComment(post)
        val b = publishedComment(post)
        h.commentService.update(third, "general", post, a.id, "a2")
        h.commentService.update(third, "general", post, b.id, "b2")
        val listed = h.commentService.listRevisions(moderator, "general", post, 0, 20)
        assertEquals(listOf("공개 댓글", "공개 댓글"), listed.values.map { it.body })
        assertEquals(listOf("a2", "b2"), listed.values.map { it.pendingRevision!!.body })
        assertEquals(BoardErrorCode.FORBIDDEN, h.failure { h.commentService.listRevisions(owner, "general", post, 0, 20) })

        events.clear()
        h.commentService.moderate(moderator, "general", post, a.id, CommentStatus.HIDDEN)
        assertEquals(1, h.store.revisions.size)
        assertEquals(RevisionOutcome.DISCARDED, events.filterIsInstance<RevisionDecided>().single().outcome)
        assertEquals(BoardErrorCode.COMMENT_NOT_FOUND, h.failure { h.commentService.decideRevision(moderator, "general", post, a.id, RevisionDecision.APPROVE, h.v(RevisionTarget.COMMENT, a.id)) })
    }

    // ---- 비회원 ----

    @Test
    fun `a guest author gets the revision only in the edit response and nobody reads it back but moderators`() {
        val guestPost = h.postService.create(null, "general", CreatePost("손님 글", "본문", null, null, guestName = "손님"), ctx()).post
        h.postService.moderate(moderator, "general", guestPost.id, PostStatus.PUBLISHED, null)
        val secretCtx = { ctx("pass1234") }
        val edited = h.postService.update(null, "general", guestPost.id, UpdatePost(null, "고친 본문", null, null), secretCtx())
        assertEquals("본문", edited.post.body)
        assertEquals("고친 본문", edited.pendingRevision!!.body)
        assertNull(h.postService.get(null, "general", guestPost.id).pendingRevision)
        assertEquals("고친 본문", h.postService.get(moderator, "general", guestPost.id).pendingRevision!!.body)
    }

    @Test
    fun `without a revision repository the first edit that needs one fails closed`() {
        assertFailsWith<UnsupportedOperationException> { NoBoardRevisions.submit(Revision(RevisionTarget.POST, 1, "g", 1, "t", "b", emptyList(), T0, T0)) }
        assertEquals(emptyMap(), NoBoardRevisions.find(RevisionTarget.POST, listOf(1)))
    }
}
