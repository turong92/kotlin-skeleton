package dev.sumin.skeleton.board

import kotlin.test.Test
import kotlin.test.assertEquals

class ReactionServiceTest {
    private val h = ServiceHarness()
    private fun postId() = h.newPost().id

    @Test
    fun `in SINGLE mode a new reaction replaces the old one and the same one twice is a no-op`() {
        val id = postId()
        h.reactionService.reactToPost(other, "general", id, "LIKE")
        val again = h.reactionService.reactToPost(other, "general", id, "LIKE")
        assertEquals(mapOf("LIKE" to 1L, "DISLIKE" to 0L), again.counts)

        val switched = h.reactionService.reactToPost(other, "general", id, "DISLIKE")
        assertEquals(mapOf("LIKE" to 0L, "DISLIKE" to 1L), switched.counts)
        assertEquals(setOf("DISLIKE"), switched.myReactions)
        assertEquals(1L, h.store.posts[id]!!.reactionCount)
    }

    @Test
    fun `different people react independently`() {
        val id = postId()
        h.reactionService.reactToPost(other, "general", id, "LIKE")
        val state = h.reactionService.reactToPost(owner, "general", id, "LIKE")
        assertEquals(2L, state.counts["LIKE"])
        assertEquals(setOf("LIKE"), state.myReactions)
    }

    @Test
    fun `in PER_TYPE mode one person holds several types at once and a new type needs only configuration`() {
        val perType = ServiceHarness(props = BoardProperties(reaction = BoardProperties.Reaction(listOf("LIKE", "EMPATHY", "SAD"), ReactionMode.PER_TYPE)))
        val id = perType.newPost().id
        perType.reactionService.reactToPost(other, "general", id, "LIKE")
        val state = perType.reactionService.reactToPost(other, "general", id, "EMPATHY")
        assertEquals(mapOf("LIKE" to 1L, "EMPATHY" to 1L, "SAD" to 0L), state.counts)
        assertEquals(setOf("LIKE", "EMPATHY"), state.myReactions)
        assertEquals(2L, perType.store.posts[id]!!.reactionCount)
    }

    @Test
    fun `an unknown or missing type is REACTION_TYPE_INVALID, codes are case sensitive`() {
        val id = postId()
        assertEquals(BoardErrorCode.REACTION_TYPE_INVALID, h.failure { h.reactionService.reactToPost(other, "general", id, "EMPATHY") })
        assertEquals(BoardErrorCode.REACTION_TYPE_INVALID, h.failure { h.reactionService.reactToPost(other, "general", id, "like") })
        assertEquals(BoardErrorCode.REACTION_TYPE_INVALID, h.failure { h.reactionService.reactToPost(other, "general", id, null) })
        assertEquals(BoardErrorCode.REACTION_TYPE_INVALID, h.failure { h.reactionService.removeFromPost(other, "general", id, "NOPE") })
    }

    @Test
    fun `remove drops one type, or everything when no type is given, and is a no-op when there is nothing`() {
        val id = postId()
        h.reactionService.reactToPost(other, "general", id, "LIKE")
        assertEquals(emptySet(), h.reactionService.removeFromPost(other, "general", id, null).myReactions)
        assertEquals(0L, h.store.posts[id]!!.reactionCount)
        assertEquals(emptySet(), h.reactionService.removeFromPost(other, "general", id, "LIKE").myReactions)
        h.reactionService.reactToPost(other, "general", id, "LIKE")
        assertEquals(mapOf("LIKE" to 0L, "DISLIKE" to 0L), h.reactionService.removeFromPost(other, "general", id, "LIKE").counts)
    }

    @Test
    fun `reacting needs a visible published post`() {
        val draft = h.newPost(status = PostStatus.DRAFT).id
        assertEquals(BoardErrorCode.POST_NOT_FOUND, h.failure { h.reactionService.reactToPost(other, "general", draft, "LIKE") })
        assertEquals(BoardErrorCode.POST_NOT_COMMENTABLE, h.failure { h.reactionService.reactToPost(owner, "general", draft, "LIKE") })
        assertEquals(BoardErrorCode.POST_NOT_FOUND, h.failure { h.reactionService.reactToPost(other, "general", 9999, "LIKE") })
    }

    @Test
    fun `comments take reactions with the same rules`() {
        val post = postId()
        val comment = h.commentService.create(other, "general", post, null, "hi").comment.id
        h.reactionService.reactToComment(owner, "general", post, comment, "LIKE")
        val state = h.reactionService.reactToComment(owner, "general", post, comment, "DISLIKE")
        assertEquals(mapOf("LIKE" to 0L, "DISLIKE" to 1L), state.counts)
        assertEquals(1L, h.store.comments[comment]!!.reactionCount)
        assertEquals(BoardErrorCode.COMMENT_NOT_FOUND, h.failure { h.reactionService.reactToComment(owner, "general", post, 9999, "LIKE") })

        h.commentService.moderate(moderator, "general", post, comment, CommentStatus.HIDDEN)
        assertEquals(BoardErrorCode.POST_NOT_COMMENTABLE, h.failure { h.reactionService.reactToComment(owner, "general", post, comment, "LIKE") })
    }

    @Test
    fun `reacting is refused when rate limited`() {
        val limited = ServiceHarness(limiter = { _, action -> action != "reaction" })
        val id = limited.newPost().id
        assertEquals(BoardErrorCode.RATE_LIMITED, limited.failure { limited.reactionService.reactToPost(other, "general", id, "LIKE") })
    }
}
