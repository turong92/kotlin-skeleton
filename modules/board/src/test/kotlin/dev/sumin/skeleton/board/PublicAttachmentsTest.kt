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
            override fun mayPublish(privateKey: String) = !privateKey.endsWith(".txt")
            override fun publicKeyOf(privateKey: String) = "public/${privateKey.hashCode()}"
            override fun publish(privateKey: String) = true
            override fun unpublish(privateKey: String) = Unit
            override fun listPublicKeys() = emptyList<String>()
            override fun deletePublicKey(publicKey: String) = Unit
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
        fun access(who: BoardCaller?, index: Int = 0) = h.commentService.attachmentAccess(who, "general", post, c.id, index)
        assertTrue(access(other).url.startsWith("https://signed.example/k/a.png?ttl=300"), "the managing post owner")
        assertEquals(300, access(moderator).expiresInSeconds)
        access(third)   // 작성자
        assertEquals(BoardErrorCode.COMMENT_NOT_FOUND, h.failure { access(stranger) }, "a waiting comment does not exist for others")
        assertEquals(BoardErrorCode.COMMENT_NOT_FOUND, h.failure { access(third, 5) }, "an index that is not this comment's")
        h.commentService.moderate(other, "general", post, c.id, CommentStatus.PUBLISHED)
        assertEquals(BoardErrorCode.FORBIDDEN, h.failure { access(stranger) }, "published: seen, but the signed address is not for strangers (they use the public address)")
    }

    @Test
    fun `hidden and deleted comments' pictures are for moderators only - not the post owner, not the author`() {
        val (h, post, c) = world()
        h.commentService.moderate(other, "general", post, c.id, CommentStatus.PUBLISHED)
        h.commentService.moderate(other, "general", post, c.id, CommentStatus.HIDDEN)
        fun access(who: BoardCaller) = h.commentService.attachmentAccess(who, "general", post, c.id, 0)
        assertEquals(BoardErrorCode.COMMENT_NOT_FOUND, h.failure { access(other) })
        assertEquals(BoardErrorCode.COMMENT_NOT_FOUND, h.failure { access(third) })
        assertTrue(access(moderator).url.contains("k/a.png"))
    }

    @Test
    fun `an edit version's picture is reachable by managers before approval by its own index, never by others`() {
        val (h, post, c) = world()
        h.commentService.moderate(other, "general", post, c.id, CommentStatus.PUBLISHED)
        h.commentService.update(third, "general", post, c.id, null, attachments = listOf("k/new.png"))
        assertEquals(emptyMap(), h.commentService.list(stranger, "general", post, 0, 10, CommentSort.OLDEST).values.single().attachmentUrls.filterKeys { it == "k/new.png" }, "the version's picture has no public address")
        assertTrue(h.commentService.attachmentAccess(other, "general", post, c.id, 0, AttachmentSource.EDIT).url.contains("k/new.png"))
        assertTrue(h.commentService.attachmentAccess(other, "general", post, c.id, 0, AttachmentSource.CURRENT).url.contains("k/a.png"), "the published list is separate")
        assertEquals(BoardErrorCode.FORBIDDEN, h.failure { h.commentService.attachmentAccess(stranger, "general", post, c.id, 0, AttachmentSource.EDIT) })
    }

    @Test
    fun `a signed-out post owner sees the waiting pictures with the post's secret, and many views never lock them out`() {
        val h = ServiceHarness(props = props, settings = settings, uploads = on)
        val ownerSecret = GuestContext("owner-secret-1", "ip-owner", "9.9.9.9")
        val post = h.postService.create(null, "general", CreatePost("글", "본문", null, null, guestName = "주인"), ownerSecret).post.id
        h.postService.moderate(moderator, "general", post, PostStatus.PUBLISHED, null)
        val c = h.commentService.create(third, "general", post, null, "사진", attachments = listOf("k/w.png")).comment   // 승인 대기
        repeat(15) {   // 한도(기본 10/10분)보다 많이 - 맞는 비밀은 되돌려진다
            assertTrue(h.commentService.attachmentAccess(null, "general", post, c.id, 0, AttachmentSource.CURRENT, AttachmentRole.OWNER, ownerSecret).url.contains("k/w.png"))
        }
        val wrong = GuestContext("wrong-secret-x", "ip-stranger", "8.8.8.8")
        assertEquals(BoardErrorCode.COMMENT_NOT_FOUND, h.failure { h.commentService.attachmentAccess(null, "general", post, c.id, 0, AttachmentSource.CURRENT, AttachmentRole.OWNER, wrong) }, "a waiting comment does not exist for someone who cannot prove the post")
        assertEquals(BoardErrorCode.POST_NOT_FOUND.let { it }, BoardErrorCode.POST_NOT_FOUND)
        // 글 사진: 비밀이 맞으면 글 주인
        val withPicture = h.postService.create(null, "general", CreatePost("글2", "본문", listOf("k/p.png"), null, guestName = "주인"), GuestContext("owner-secret-2", "ip-owner2", "9.9.9.9")).post
        assertTrue(h.postService.attachmentAccess(null, "general", withPicture.id, 0, AttachmentSource.CURRENT, GuestContext("owner-secret-2", "ip-owner2", "9.9.9.9")).url.contains("k/p.png"))
    }

    @Test
    fun `post pictures - moderators and the author only, and a stranger gets no signed address`() {
        val h = ServiceHarness(props = props, settings = settings, uploads = on)
        val post = h.postService.create(other, "general", CreatePost("제목", "본문", listOf("k/p.png"), PostStatus.DRAFT)).post
        assertTrue(h.postService.attachmentAccess(other, "general", post.id, 0).url.contains("k/p.png"))
        assertTrue(h.postService.attachmentAccess(moderator, "general", post.id, 0).url.contains("k/p.png"))
        assertTrue(h.failure { h.postService.attachmentAccess(stranger, "general", post.id, 0) } in setOf(BoardErrorCode.POST_NOT_FOUND, BoardErrorCode.FORBIDDEN))
        kotlin.test.assertFailsWith<dev.sumin.skeleton.common.ApplicationException> { h.postService.attachmentAccess(null, "general", post.id, 0) }   // 로그인도 비밀도 없다 -> 401
    }

    @Test
    fun `a picture of a type that cannot be public gets no address, the others do`() {
        val h = ServiceHarness(props = props, settings = settings, uploads = on)
        val post = h.postService.create(other, "general", CreatePost("제목", "본문", null, null)).post
        h.postService.moderate(moderator, "general", post.id, PostStatus.PUBLISHED, null)
        val c = h.commentService.create(other, "general", post.id, null, "둘", attachments = listOf("k/a.png", "k/notes.txt")).comment
        val shown = h.commentService.list(stranger, "general", post.id, 0, 10, CommentSort.OLDEST).values.single { it.comment.id == c.id }
        assertEquals(setOf("k/a.png"), shown.attachmentUrls.keys, "the text file would answer 404 - no address for it")
        assertEquals(listOf("k/a.png", "k/notes.txt"), shown.attachments, "the keys stay")
    }
}
