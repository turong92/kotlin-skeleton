package dev.sumin.skeleton.board

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PostServiceTest {
    private val h = ServiceHarness()

    @Test
    fun `create publishes by default, trims and records the author`() {
        val view = h.postService.create(owner, "general", CreatePost("  Hello \u0000", " body ", listOf("a/b.png"), null))
        assertEquals(PostStatus.PUBLISHED, view.post.status)
        assertEquals("Hello", view.post.title)
        assertEquals("body", view.post.body)
        assertEquals("acc_author", view.post.authorId)
        assertEquals(listOf("a/b.png"), view.post.attachments)
        assertEquals(mapOf("LIKE" to 0L, "DISLIKE" to 0L), view.reactions.counts)
    }

    @Test
    fun `create supports DRAFT and refuses other statuses`() {
        assertEquals(PostStatus.DRAFT, h.newPost(status = PostStatus.DRAFT).status)
        assertEquals(BoardErrorCode.CONTENT_INVALID, h.failure { h.newPost(status = PostStatus.HIDDEN) })
    }

    @Test
    fun `create rejects blank content and unknown boards and denied attachments`() {
        assertEquals(BoardErrorCode.CONTENT_INVALID, h.failure { h.newPost(title = " ") })
        assertEquals(BoardErrorCode.CONTENT_INVALID, h.failure { h.newPost(body = "") })
        assertEquals(BoardErrorCode.NOT_FOUND, h.failure { h.postService.create(owner, "nope", CreatePost("t", "b", null, null)) })
        val denying = ServiceHarness(policy = object : BoardPolicy by DefaultBoardPolicy() {
            override fun canAttach(caller: BoardCaller, keys: List<String>) = false
        })
        assertEquals(BoardErrorCode.FORBIDDEN, denying.failure { denying.postService.create(owner, "general", CreatePost("t", "b", listOf("k"), null)) })
    }

    @Test
    fun `create is refused when the rate limiter says no`() {
        val limited = ServiceHarness(limiter = { _, _ -> false })
        assertEquals(BoardErrorCode.RATE_LIMITED, limited.failure { limited.newPost() })
    }

    @Test
    fun `get counts a view per call and shows published posts to anyone`() {
        val id = h.newPost().id
        assertEquals(1, h.postService.get(null, "general", id).post.viewCount)
        assertEquals(2, h.postService.get(other, "general", id).post.viewCount)
    }

    @Test
    fun `a draft hidden or deleted post is 404 for others but visible to its author and moderators`() {
        val draft = h.newPost(status = PostStatus.DRAFT).id
        assertEquals(BoardErrorCode.POST_NOT_FOUND, h.failure { h.postService.get(other, "general", draft) })
        assertEquals(BoardErrorCode.POST_NOT_FOUND, h.failure { h.postService.get(null, "general", draft) })
        assertEquals(draft, h.postService.get(owner, "general", draft).post.id)
        assertEquals(draft, h.postService.get(moderator, "general", draft).post.id)
    }

    @Test
    fun `a post is not found under another board code`() {
        h.boardService.create(moderator, "other-board", "Other", null)
        val id = h.newPost().id
        assertEquals(BoardErrorCode.POST_NOT_FOUND, h.failure { h.postService.get(owner, "other-board", id) })
    }

    @Test
    fun `list shows published posts only and pinned ones first`() {
        val a = h.newPost(title = "a").id
        val b = h.newPost(title = "b").id
        h.newPost(title = "draft", status = PostStatus.DRAFT)
        h.postService.moderate(moderator, "general", a, null, true)

        val page = h.postService.list(other, "general", PostListRequest(0, 20))
        assertEquals(listOf(a, b), page.values.map { it.item.id })
        assertEquals(2, page.totalElements)
        assertTrue(page.values.first().item.pinned)
    }

    @Test
    fun `list status filter is for moderators, mine lists the author's own drafts`() {
        val draft = h.newPost(title = "mine-draft", status = PostStatus.DRAFT).id
        val hidden = h.newPost(title = "bad").id
        h.postService.moderate(moderator, "general", hidden, PostStatus.HIDDEN, null)

        assertEquals(BoardErrorCode.FORBIDDEN, h.failure { h.postService.list(other, "general", PostListRequest(0, 20, status = PostStatus.HIDDEN)) })
        assertEquals(BoardErrorCode.FORBIDDEN, h.failure { h.postService.list(null, "general", PostListRequest(0, 20, status = PostStatus.DRAFT)) })
        assertEquals(listOf(hidden), h.postService.list(moderator, "general", PostListRequest(0, 20, status = PostStatus.HIDDEN)).values.map { it.item.id })
        assertEquals(setOf(draft, hidden), h.postService.list(owner, "general", PostListRequest(0, 20, mine = true)).values.map { it.item.id }.toSet())
        assertEquals(emptyList(), h.postService.list(other, "general", PostListRequest(0, 20, mine = true)).values)
    }

    @Test
    fun `list searches title and body, and the page size is clamped by max-page-size`() {
        h.newPost(title = "kotlin tips", body = "x")
        h.newPost(title = "other", body = "about KOTLIN")
        h.newPost(title = "none", body = "none")
        assertEquals(2, h.postService.list(other, "general", PostListRequest(0, 20, q = " kotlin ")).totalElements)

        val small = ServiceHarness(props = BoardProperties(maxPageSize = 2))
        repeat(3) { small.newPost() }
        assertEquals(2, small.postService.list(other, "general", PostListRequest(0, 100)).values.size)
    }

    @Test
    fun `list sorts by reactions comments and by one reaction type`() {
        val a = h.newPost(title = "a").id
        val b = h.newPost(title = "b").id
        h.reactionService.reactToPost(other, "general", b, "DISLIKE")
        h.reactionService.reactToPost(owner, "general", a, "LIKE")
        h.reactionService.reactToPost(other, "general", a, "LIKE")
        h.commentService.create(other, "general", b, null, "hi")

        assertEquals(listOf(a, b), h.postService.list(owner, "general", PostListRequest(0, 20, sort = PostSort.REACTIONS)).values.map { it.item.id })
        assertEquals(listOf(b, a), h.postService.list(owner, "general", PostListRequest(0, 20, sort = PostSort.COMMENTS)).values.map { it.item.id })
        assertEquals(listOf(b, a), h.postService.list(owner, "general", PostListRequest(0, 20, sort = PostSort.REACTIONS, reaction = "DISLIKE")).values.map { it.item.id })
        assertEquals(BoardErrorCode.REACTION_TYPE_INVALID, h.failure { h.postService.list(owner, "general", PostListRequest(0, 20, reaction = "NOPE")) })
    }

    @Test
    fun `list items carry reaction counts, my reactions and the excerpt`() {
        val id = h.newPost(body = "x".repeat(500)).id
        h.reactionService.reactToPost(other, "general", id, "LIKE")
        val item = h.postService.list(other, "general", PostListRequest(0, 20)).values.single()
        assertEquals(140, item.item.excerpt.length)
        assertEquals(1L, item.reactions.counts["LIKE"])
        assertEquals(setOf("LIKE"), item.reactions.myReactions)
        assertEquals(emptySet(), h.postService.list(owner, "general", PostListRequest(0, 20)).values.single().reactions.myReactions)
        assertEquals(emptySet(), h.postService.list(null, "general", PostListRequest(0, 20)).values.single().reactions.myReactions)
    }

    @Test
    fun `the author edits title body and publishes a draft, others cannot`() {
        val id = h.newPost(status = PostStatus.DRAFT).id
        val updated = h.postService.update(owner, "general", id, UpdatePost("New", null, null, PostStatus.PUBLISHED))
        assertEquals("New", updated.post.title)
        assertEquals("World", updated.post.body)
        assertEquals(PostStatus.PUBLISHED, updated.post.status)
        assertEquals(BoardErrorCode.FORBIDDEN, h.failure { h.postService.update(other, "general", id, UpdatePost("x", null, null, null)) })
        assertEquals(BoardErrorCode.CONTENT_INVALID, h.failure { h.postService.update(owner, "general", id, UpdatePost(null, null, null, PostStatus.HIDDEN)) })
        assertEquals("Mod", h.postService.update(moderator, "general", id, UpdatePost("Mod", null, null, null)).post.title)
    }

    @Test
    fun `delete is soft, by the author or a moderator`() {
        val a = h.newPost().id
        val b = h.newPost().id
        assertEquals(BoardErrorCode.FORBIDDEN, h.failure { h.postService.delete(other, "general", a) })
        h.postService.delete(owner, "general", a)
        h.postService.delete(moderator, "general", b)
        assertEquals(PostStatus.DELETED, h.store.posts[a]!!.status)
        assertEquals(BoardErrorCode.POST_NOT_FOUND, h.failure { h.postService.get(other, "general", a) })
        assertEquals(PostStatus.DELETED, h.postService.get(owner, "general", a).post.status)
    }

    @Test
    fun `moderation hides and pins, for moderators only`() {
        val id = h.newPost().id
        assertEquals(BoardErrorCode.FORBIDDEN, h.failure { h.postService.moderate(owner, "general", id, PostStatus.HIDDEN, null) })
        val hidden = h.postService.moderate(moderator, "general", id, PostStatus.HIDDEN, true)
        assertEquals(PostStatus.HIDDEN, hidden.post.status)
        assertTrue(hidden.post.pinned)
        assertEquals(BoardErrorCode.POST_NOT_FOUND, h.failure { h.postService.get(other, "general", id) })
        assertFalse(h.postService.moderate(moderator, "general", id, PostStatus.PUBLISHED, false).post.pinned)
        assertEquals(BoardErrorCode.CONTENT_INVALID, h.failure { h.postService.moderate(moderator, "general", id, PostStatus.DRAFT, null) })
    }

    @Test
    fun `an author cannot edit a post moderators hid`() {
        val id = h.newPost().id
        h.postService.moderate(moderator, "general", id, PostStatus.HIDDEN, null)
        assertEquals(BoardErrorCode.FORBIDDEN, h.failure { h.postService.update(owner, "general", id, UpdatePost("x", null, null, null)) })
    }
}
