package dev.sumin.skeleton.board

import dev.sumin.skeleton.common.upload.PrivateUploadAccess
import dev.sumin.skeleton.common.upload.PublicUploads
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 사진은 공개된 항목만 공개 주소로 나가고, 승인 전 · 판의 사진은 관리하는 사람 · 작성자만 짧은 서명 주소로 본다 */
class PublicAttachmentsTest {
    private val props = BoardProperties(commentMaxAttachments = 2, guest = BoardProperties.Guest(enabled = true))
    private val settings = BoardSettings(guestWrite = GuestWrite.ALL, commentApproval = ApprovalMode.ALL, ownerModeratesComments = true)
    private val third = BoardCaller("acc_third")
    private val stranger = BoardCaller("acc_stranger")

    private val on = BoardUploads(
        publicUploads = { object : PublicUploads {
            override fun urlOf(privateKey: String) = "https://cdn.example/${privateKey.hashCode()}"
            override fun publish(privateKey: String) = Unit
            override fun unpublish(privateKey: String) = Unit
        } },
        access = { object : PrivateUploadAccess {
            override fun downloadUrl(privateKey: String, ttl: Duration) = "https://signed.example/$privateKey?ttl=${ttl.seconds}"
        } },
    )

    private fun world(uploads: BoardUploads = on): Triple<ServiceHarness, Long, Comment> {
        val h = ServiceHarness(props = props, settings = settings, uploads = uploads)
        val post = h.postService.create(other, "general", CreatePost("제목", "본문", null, null)).post
        h.postService.moderate(moderator, "general", post.id, PostStatus.PUBLISHED, null)
        val c = h.commentService.create(third, "general", post.id, null, "사진", attachments = listOf("k/a.png")).comment
        return Triple(h, post.id, c)
    }

    @Test
    fun `a waiting comment has no public address, an approved one has - and with the feature off there are none at all`() {
        val (h, post, c) = world()
        fun urls(who: BoardCaller) = h.commentService.list(who, "general", post, 0, 10, CommentSort.OLDEST).values.single().attachmentUrls
        assertEquals(emptyMap(), urls(third), "waiting comment: the picture is private")
        assertEquals(emptyMap(), urls(other))
        h.commentService.moderate(other, "general", post, c.id, CommentStatus.PUBLISHED)
        assertEquals(setOf("k/a.png"), urls(stranger).keys)
        h.commentService.moderate(other, "general", post, c.id, CommentStatus.HIDDEN)
        assertEquals(emptyMap(), h.commentService.list(other, "general", post, 0, 10, CommentSort.OLDEST).values.single().attachmentUrls, "hidden: no address")
        val (off, offPost, offComment) = world(BoardUploads.NONE)
        off.commentService.moderate(other, "general", offPost, offComment.id, CommentStatus.PUBLISHED)
        assertEquals(emptyMap(), off.commentService.list(stranger, "general", offPost, 0, 10, CommentSort.OLDEST).values.single().attachmentUrls)
    }

    @Test
    fun `only managers and the author get a signed address for a waiting picture - a stranger cannot even see the comment`() {
        val (h, post, c) = world()
        fun access(who: BoardCaller?, key: String = "k/a.png") = h.commentService.attachmentAccess(who, "general", post, c.id, key)
        assertTrue(access(other).url.startsWith("https://signed.example/k/a.png?ttl=300"), "the managing post owner")
        assertEquals(300, access(moderator).expiresInSeconds)
        access(third)   // 작성자
        assertEquals(BoardErrorCode.COMMENT_NOT_FOUND, h.failure { access(stranger) }, "a waiting comment does not exist for others")
        assertEquals(BoardErrorCode.COMMENT_NOT_FOUND, h.failure { access(third, "k/other.png") }, "a key that is not this comment's")
        h.commentService.moderate(other, "general", post, c.id, CommentStatus.PUBLISHED)
        assertEquals(BoardErrorCode.FORBIDDEN, h.failure { access(stranger) }, "published: seen, but the signed address is not for strangers (they use the public address)")
    }

    @Test
    fun `an edit version's picture is reachable by managers before approval, never by others`() {
        val (h, post, c) = world()
        h.commentService.moderate(other, "general", post, c.id, CommentStatus.PUBLISHED)
        h.commentService.update(third, "general", post, c.id, null, attachments = listOf("k/new.png"))
        assertEquals(emptyMap(), h.commentService.list(stranger, "general", post, 0, 10, CommentSort.OLDEST).values.single().attachmentUrls.filterKeys { it == "k/new.png" }, "the version's picture has no public address")
        assertTrue(h.commentService.attachmentAccess(other, "general", post, c.id, "k/new.png").url.contains("k/new.png"))
        assertEquals(BoardErrorCode.FORBIDDEN, h.failure { h.commentService.attachmentAccess(stranger, "general", post, c.id, "k/new.png") })
    }

    @Test
    fun `post pictures - moderators and the author only, and a stranger gets no signed address`() {
        val h = ServiceHarness(props = props, settings = settings, uploads = on)
        val post = h.postService.create(other, "general", CreatePost("제목", "본문", listOf("k/p.png"), PostStatus.DRAFT)).post
        assertTrue(h.postService.attachmentAccess(other, "general", post.id, "k/p.png").url.contains("k/p.png"))
        assertTrue(h.postService.attachmentAccess(moderator, "general", post.id, "k/p.png").url.contains("k/p.png"))
        assertTrue(h.failure { h.postService.attachmentAccess(stranger, "general", post.id, "k/p.png") } in setOf(BoardErrorCode.POST_NOT_FOUND, BoardErrorCode.FORBIDDEN))
        kotlin.test.assertFailsWith<dev.sumin.skeleton.common.ApplicationException> { h.postService.attachmentAccess(null, "general", post.id, "k/p.png") }   // 로그인도 비밀도 없다 -> 401
    }
}
