package dev.sumin.skeleton.board

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultBoardPolicyTest {
    private val policy = DefaultBoardPolicy()

    @Test
    fun `anyone may read a published post, anonymous included`() {
        assertTrue(policy.canViewPost(null, aPost()))
        assertTrue(policy.canViewPost(other, aPost()))
    }

    @Test
    fun `draft hidden and deleted posts are visible to the author and moderators only`() {
        for (status in listOf(PostStatus.DRAFT, PostStatus.HIDDEN, PostStatus.DELETED)) {
            val post = aPost(status = status)
            assertFalse(policy.canViewPost(null, post), "anonymous $status")
            assertFalse(policy.canViewPost(other, post), "other $status")
            assertTrue(policy.canViewPost(owner, post), "owner $status")
            assertTrue(policy.canViewPost(moderator, post), "moderator $status")
        }
    }

    @Test
    fun `only the author edits own post content and only while it is not moderated away`() {
        assertTrue(policy.canEditPost(owner, aPost()))
        assertTrue(policy.canEditPost(owner, aPost(status = PostStatus.DRAFT)))
        assertFalse(policy.canEditPost(owner, aPost(status = PostStatus.HIDDEN)))
        assertFalse(policy.canEditPost(owner, aPost(status = PostStatus.DELETED)))
        assertFalse(policy.canEditPost(other, aPost()))
        assertTrue(policy.canEditPost(moderator, aPost()))
    }

    @Test
    fun `author and moderator delete posts, others do not`() {
        assertTrue(policy.canDeletePost(owner, aPost()))
        assertTrue(policy.canDeletePost(moderator, aPost()))
        assertFalse(policy.canDeletePost(other, aPost()))
    }

    @Test
    fun `moderation and board creation are moderator only`() {
        assertTrue(policy.canModerate(moderator))
        assertFalse(policy.canModerate(owner))
        assertTrue(policy.canCreateBoard(moderator))
        assertFalse(policy.canCreateBoard(owner))
    }

    @Test
    fun `any signed in user posts, comments and reacts`() {
        assertTrue(policy.canCreatePost(other, aBoard))
        assertTrue(policy.canComment(other, aPost()))
        assertTrue(policy.canReact(other))
        assertTrue(policy.canAttach(other, listOf("k")))
    }

    @Test
    fun `comment text is edited by its author only, deleted by author or moderator`() {
        assertTrue(policy.canEditComment(owner, aComment()))
        assertFalse(policy.canEditComment(moderator, aComment()))
        assertFalse(policy.canEditComment(other, aComment()))
        assertFalse(policy.canEditComment(owner, aComment(status = CommentStatus.DELETED)))
        assertTrue(policy.canDeleteComment(owner, aComment()))
        assertTrue(policy.canDeleteComment(moderator, aComment()))
        assertFalse(policy.canDeleteComment(other, aComment()))
    }
}
