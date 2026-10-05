package dev.sumin.skeleton.board

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommentServiceTest {
    private val notices = mutableListOf<BoardCommentNotice>()
    private val h = ServiceHarness(notifier = { notices += it })
    private val postId = h.newPost(owner).id

    private fun comment(caller: BoardCaller = other, body: String = "hi", parent: Long? = null, post: Long = postId) =
        h.commentService.create(caller, "general", post, parent, body)

    @Test
    fun `a top-level comment has depth 0 and is its own root, and the post counts it`() {
        val c = comment().comment
        assertEquals(0, c.depth)
        assertNull(c.parentId)
        assertEquals(c.id, c.rootId)
        assertEquals(1L, h.store.posts[postId]!!.commentCount)
    }

    @Test
    fun `replies nest up to max-comment-depth and one level deeper is COMMENT_TOO_DEEP`() {
        val root = comment().comment
        val reply = comment(parent = root.id).comment
        val deep = comment(parent = reply.id).comment
        assertEquals(listOf(1, 2), listOf(reply.depth, deep.depth))
        assertEquals(setOf(root.id), setOf(reply.rootId, deep.rootId))
        assertEquals(BoardErrorCode.COMMENT_TOO_DEEP, h.failure { comment(parent = deep.id) })
    }

    @Test
    fun `the depth cap is configuration - 0 means no replies, 3 allows one more level`() {
        val flat = ServiceHarness(props = BoardProperties(maxCommentDepth = 0))
        val p = flat.newPost().id
        val root = flat.commentService.create(other, "general", p, null, "x").comment
        assertEquals(BoardErrorCode.COMMENT_TOO_DEEP, flat.failure { flat.commentService.create(owner, "general", p, root.id, "y") })

        val deeper = ServiceHarness(props = BoardProperties(maxCommentDepth = 3))
        val q = deeper.newPost().id
        var parent: Long? = null
        repeat(4) { parent = deeper.commentService.create(other, "general", q, parent, "n").comment.id }
        assertEquals(3, deeper.store.comments[parent]!!.depth)
    }

    @Test
    fun `a parent must exist in the same post and still be published`() {
        val otherPost = h.newPost().id
        val foreign = comment(post = otherPost).comment.id
        assertEquals(BoardErrorCode.COMMENT_NOT_FOUND, h.failure { comment(parent = foreign) })
        assertEquals(BoardErrorCode.COMMENT_NOT_FOUND, h.failure { comment(parent = 9999) })
        val root = comment().comment.id
        h.commentService.delete(other, "general", postId, root)
        assertEquals(BoardErrorCode.POST_NOT_COMMENTABLE, h.failure { comment(parent = root) })
    }

    @Test
    fun `a draft or hidden post cannot be commented on`() {
        val draft = h.newPost(status = PostStatus.DRAFT).id
        assertEquals(BoardErrorCode.POST_NOT_COMMENTABLE, h.failure { comment(owner, post = draft) })
        assertEquals(BoardErrorCode.POST_NOT_FOUND, h.failure { comment(other, post = draft) })
        h.postService.moderate(moderator, "general", postId, PostStatus.HIDDEN, null)
        assertEquals(BoardErrorCode.POST_NOT_FOUND, h.failure { comment() })
    }

    @Test
    fun `blank or too long comments are CONTENT_INVALID and the limiter is honoured`() {
        assertEquals(BoardErrorCode.CONTENT_INVALID, h.failure { comment(body = "  ") })
        assertEquals(BoardErrorCode.CONTENT_INVALID, h.failure { comment(body = "x".repeat(2001)) })
        val limited = ServiceHarness(limiter = { _, _ -> false })
        val p = limited.newPostForced()
        assertEquals(BoardErrorCode.RATE_LIMITED, limited.failure { limited.commentService.create(other, "general", p, null, "hi") })
    }

    @Test
    fun `the thread listing pages top-level comments and returns all descendants flattened in order`() {
        val a = comment(body = "a").comment
        val b = comment(body = "b").comment
        val a1 = comment(owner, "a1", a.id).comment
        val a11 = comment(other, "a11", a1.id).comment
        val a2 = comment(other, "a2", a.id).comment

        val page = h.commentService.list(owner, "general", postId, 0, 1, CommentSort.OLDEST)
        assertEquals(2, page.totalElements)
        val thread = page.values.single()
        assertEquals(a.id, thread.comment.id)
        assertEquals(listOf(a1.id, a11.id, a2.id), thread.replies.map { it.comment.id })
        assertEquals(listOf(a.id, a1.id, a1.id), listOf(thread.comment.id, thread.replies[1].comment.parentId, thread.replies[1].comment.parentId))
        assertEquals(3, thread.replyCount)
        assertEquals(1, thread.replies[0].replyCount)
        assertEquals(emptyList(), thread.replies[0].replies)
        assertEquals(b.id, h.commentService.list(owner, "general", postId, 1, 1, CommentSort.OLDEST).values.single().comment.id)
        assertEquals(b.id, h.commentService.list(owner, "general", postId, 0, 1, CommentSort.LATEST).values.single().comment.id)
    }

    @Test
    fun `a deleted or hidden comment stays in the thread with its body hidden`() {
        val root = comment(body = "secret").comment
        val reply = comment(owner, "kept", root.id).comment
        h.commentService.delete(other, "general", postId, root.id)
        h.commentService.moderate(moderator, "general", postId, reply.id, CommentStatus.HIDDEN)

        val thread = h.commentService.list(owner, "general", postId, 0, 20, CommentSort.OLDEST).values.single()
        assertNull(thread.body)
        assertEquals(CommentStatus.DELETED, thread.comment.status)
        assertNull(thread.replies.single().body)
        assertEquals(CommentStatus.HIDDEN, thread.replies.single().comment.status)
        assertEquals(1, thread.replyCount)
        assertEquals(0L, h.store.posts[postId]!!.commentCount)
    }

    @Test
    fun `listing carries reactions per comment and sorts roots by reactions`() {
        val a = comment(body = "a").comment
        val b = comment(body = "b").comment
        h.reactionService.reactToComment(owner, "general", postId, b.id, "LIKE")
        val page = h.commentService.list(owner, "general", postId, 0, 20, CommentSort.REACTIONS)
        assertEquals(listOf(b.id, a.id), page.values.map { it.comment.id })
        assertEquals(1L, page.values.first().reactions.counts["LIKE"])
        assertEquals(setOf("LIKE"), page.values.first().reactions.myReactions)
    }

    @Test
    fun `comments of a post the caller cannot see are 404`() {
        h.postService.moderate(moderator, "general", postId, PostStatus.HIDDEN, null)
        assertEquals(BoardErrorCode.POST_NOT_FOUND, h.failure { h.commentService.list(other, "general", postId, 0, 20, CommentSort.OLDEST) })
    }

    @Test
    fun `only the author edits a comment and a deleted one stays closed`() {
        val c = comment().comment
        assertEquals("edited", h.commentService.update(other, "general", postId, c.id, " edited ").body)
        assertEquals(BoardErrorCode.FORBIDDEN, h.failure { h.commentService.update(owner, "general", postId, c.id, "x") })
        assertEquals(BoardErrorCode.FORBIDDEN, h.failure { h.commentService.update(moderator, "general", postId, c.id, "x") })
        h.commentService.delete(other, "general", postId, c.id)
        assertEquals(BoardErrorCode.FORBIDDEN, h.failure { h.commentService.update(other, "general", postId, c.id, "x") })
    }

    @Test
    fun `the author or a moderator deletes, others cannot, and a moderator can restore`() {
        val c = comment().comment
        assertEquals(BoardErrorCode.FORBIDDEN, h.failure { h.commentService.delete(owner, "general", postId, c.id) })
        h.commentService.delete(moderator, "general", postId, c.id)
        assertEquals(CommentStatus.DELETED, h.store.comments[c.id]!!.status)
        assertEquals(BoardErrorCode.FORBIDDEN, h.failure { h.commentService.moderate(other, "general", postId, c.id, CommentStatus.PUBLISHED) })
        h.commentService.moderate(moderator, "general", postId, c.id, CommentStatus.PUBLISHED)
        assertEquals(1L, h.store.posts[postId]!!.commentCount)
    }

    @Test
    fun `commenting on someone's post notifies the post author, replying notifies the parent's author`() {
        val root = comment(other, "first").comment
        assertEquals(listOf("acc_author"), notices.map { it.recipientId })
        assertEquals(root.id, notices.single().comment.id)

        val mod = comment(moderator, "reply", root.id).comment
        assertEquals("acc_other", notices.last().recipientId)
        assertEquals(root.id, notices.last().parent!!.id)
        assertEquals(mod.id, notices.last().comment.id)
    }

    @Test
    fun `nobody is notified about their own comments`() {
        val mine = comment(owner, "on my own post").comment
        comment(owner, "reply to myself", mine.id)
        assertTrue(notices.isEmpty())
    }

    @Test
    fun `a failing notifier does not fail the comment`() {
        val broken = ServiceHarness(notifier = { error("boom") })
        val p = broken.newPost().id
        assertEquals(1L, broken.commentService.create(other, "general", p, null, "hi").comment.let { broken.store.posts[p]!!.commentCount })
    }
}
