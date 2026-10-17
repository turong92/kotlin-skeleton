package dev.sumin.skeleton.board

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.PlatformErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 비회원 작성(스펙 §2 · §7 · §10): 남의 행 건드리기 · 비밀 대입 · 누출 · 캡차 · 기본값 */
class GuestWriteTest {
    private val guestOn = BoardProperties(guest = BoardProperties.Guest(enabled = true))
    private val allowAll = BoardSettings(guestWrite = GuestWrite.ALL)
    private fun harness(
        settings: BoardSettings = allowAll,
        props: BoardProperties = guestOn,
        captcha: BoardCaptcha? = null,
        notifier: BoardNotifier = NoopBoardNotifier,
        listeners: List<BoardEventListener> = emptyList(),
        secrets: BoardGuestSecrets = DefaultBoardGuestSecrets(props.guest.secret),
    ) = ServiceHarness(props = props, settings = settings, captcha = captcha, notifier = notifier, listeners = listeners, secrets = secrets)

    private var addresses = 0

    /** 요청마다 다른 주소 — 쓰기 한도(5/분)는 주소별이라 시험이 일부러 같은 주소를 쓸 때만 겹치게 한다 */
    private fun ctx(secret: String? = "pass1234", key: String = "ip-${addresses++}", token: String? = null) = GuestContext(secret, key, "10.0.0.1", token)

    private fun ServiceHarness.guestPost(secret: String? = "pass1234", name: String = "손님", key: String = "ip-${addresses++}") =
        postService.create(null, "general", CreatePost("제목", "본문", null, null, guestName = name), ctx(secret, key))

    private fun ServiceHarness.guestComment(postId: Long, secret: String? = "pass1234", name: String = "손님", key: String = "ip-${addresses++}", parent: Long? = null) =
        commentService.create(null, "general", postId, parent, "댓글", guestName = name, guest = ctx(secret, key))

    private fun unauthorized(block: () -> Unit) =
        assertEquals(PlatformErrorCode.UNAUTHORIZED, assertFailsWith<ApplicationException> { block() }.errorCode)

    // ---- 쓰기 모양 ----

    @Test
    fun `a guest post is a guest row - new guest id, the name, no profile and not mine`() {
        val h = harness()
        val a = h.guestPost()
        val b = h.guestPost()
        assertTrue(a.post.authorId.startsWith("guest:"))
        assertNotEquals(a.post.authorId, b.post.authorId, "every guest row gets a fresh id so two posts never link")
        assertEquals(a.post.authorId, a.publicAuthorId)
        assertEquals("손님", a.author?.name)
        assertNull(a.author?.tag)
        assertFalse(a.mine)
        assertEquals(PostStatus.PUBLISHED, a.post.status)
        assertTrue(h.posts.guestSecretHash(a.post.id)!!.startsWith("{bcrypt}"))
    }

    @Test
    fun `a guest row keeps its guest id as the public id even when author identities are active`() {
        assertEquals("guest:01abc", BoardAuthors.publicId("prf_x", "guest:01abc", BoardAuthors.POST_ROW, 7, identitiesPresent = true))
        assertEquals("guest:01abc", BoardAuthors.publicId(null, "guest:01abc", BoardAuthors.COMMENT_ROW, 7, identitiesPresent = false))
    }

    @Test
    fun `signed-in writers cannot send a guest name and guests cannot pick an author profile or save a draft`() {
        val h = harness()
        assertEquals(BoardErrorCode.CONTENT_INVALID, h.failure { h.postService.create(owner, "general", CreatePost("t", "b", null, null, guestName = "x")) })
        val post = h.newPost().id
        assertEquals(BoardErrorCode.CONTENT_INVALID, h.failure { h.commentService.create(other, "general", post, null, "c", guestName = "x") })
        assertEquals(BoardErrorCode.AUTHOR_NOT_ALLOWED, h.failure {
            h.postService.create(null, "general", CreatePost("t", "b", null, null, authorId = "prf_1", guestName = "x"), ctx())
        })
        assertEquals(BoardErrorCode.CONTENT_INVALID, h.failure {
            h.postService.create(null, "general", CreatePost("t", "b", null, PostStatus.DRAFT, guestName = "x"), ctx())
        })
    }

    @Test
    fun `a guest needs a valid name and a secret in the allowed shape`() {
        val h = harness()
        assertEquals(BoardErrorCode.CONTENT_INVALID, h.failure { h.guestPost(name = "  ") })
        assertEquals(BoardErrorCode.CONTENT_INVALID, h.failure { h.guestPost(name = "x".repeat(21)) })
        assertEquals(BoardErrorCode.CONTENT_INVALID, h.failure { h.guestPost(secret = null) })
        assertEquals(BoardErrorCode.CONTENT_INVALID, h.failure { h.guestPost(secret = "abc") })
        assertEquals(BoardErrorCode.CONTENT_INVALID, h.failure { h.guestPost(secret = "가".repeat(25)) }, "73+ bytes")
    }

    @Test
    fun `TOKEN mode wants a machine made random value and stores a sha256`() {
        val h = harness(props = BoardProperties(guest = BoardProperties.Guest(enabled = true, secret = GuestSecretMode.TOKEN)))
        assertEquals(BoardErrorCode.CONTENT_INVALID, h.failure { h.guestPost(secret = "pass1234") })
        val token = "a".repeat(43)
        val post = h.guestPost(secret = token)
        assertTrue(h.posts.guestSecretHash(post.post.id)!!.startsWith("{sha256}"))
        h.postService.update(null, "general", post.post.id, UpdatePost("새 제목", null, null, null), ctx(token))
    }

    @Test
    fun `changing the secret mode does not lock out rows saved under the old one`() {
        val password = DefaultBoardGuestSecrets(GuestSecretMode.PASSWORD)
        val token = DefaultBoardGuestSecrets(GuestSecretMode.TOKEN)
        val raw = "b".repeat(40)
        assertTrue(token.matches("pass1234", password.hash("pass1234")))
        assertTrue(password.matches(raw, token.hash(raw)))
        assertFalse(token.matches("wrong-one", password.hash("pass1234")))
        assertFalse(password.matches("pass1234", "no-prefix"))
    }

    // ---- 게시판 · 켜짐 ----

    @Test
    fun `guests are refused with the same 401 as today when the module, the board or the context says no`() {
        val h = harness()
        unauthorized { h.postService.create(null, "general", CreatePost("t", "b", null, null, guestName = "x"), null) }

        val off = ServiceHarness()   // guest.enabled 기본 false
        unauthorized { off.postService.create(null, "general", CreatePost("t", "b", null, null, guestName = "x"), ctx()) }

        val none = harness(settings = BoardSettings())   // 게시판 guest_write = NONE
        unauthorized { none.postService.create(null, "general", CreatePost("t", "b", null, null, guestName = "x"), ctx()) }
        val post = none.newPost().id
        unauthorized { none.commentService.create(null, "general", post, null, "c", guestName = "x", guest = ctx()) }
    }

    @Test
    fun `on a COMMENTS board guests comment but cannot post`() {
        val h = harness(settings = BoardSettings(guestWrite = GuestWrite.COMMENTS))
        assertEquals(BoardErrorCode.FORBIDDEN, h.failure { h.guestPost() })
        val post = h.newPost().id
        assertTrue(h.guestComment(post).comment.authorId.startsWith("guest:"))
    }

    @Test
    fun `the guest attach policy can narrow what guests attach`() {
        val h = ServiceHarness(
            props = guestOn, settings = allowAll,
            policy = object : BoardPolicy by DefaultBoardPolicy() {
                override fun canGuestAttach(keys: List<String>) = keys.all { it.startsWith("guest-uploads/") }
            },
        )
        assertEquals(BoardErrorCode.FORBIDDEN, h.failure {
            h.postService.create(null, "general", CreatePost("t", "b", listOf("acc_other/secret.png"), null, guestName = "x"), ctx())
        })
        h.postService.create(null, "general", CreatePost("t", "b", listOf("guest-uploads/1.png"), null, guestName = "x"), ctx())
    }

    // ---- 남의 행 건드리기 ----

    @Test
    fun `a wrong secret, another guest's secret, a member row, a missing header and a member editing a guest row are all the same 403 and change nothing`() {
        val h = harness()
        val mine = h.guestPost(secret = "right-secret")
        val theirs = h.guestPost(secret = "their-secret")
        val member = h.newPost(owner)
        val patch = UpdatePost("고침", null, null, null)

        val messages = listOf(
            h.forbiddenMessage { h.postService.update(null, "general", mine.post.id, patch, ctx("wrong-secret")) },
            h.forbiddenMessage { h.postService.update(null, "general", mine.post.id, patch, ctx("their-secret")) },
            h.forbiddenMessage { h.postService.update(null, "general", mine.post.id, patch, ctx(null)) },
            h.forbiddenMessage { h.postService.update(null, "general", member.id, patch, ctx("right-secret")) },
        )
        assertEquals(setOf(BoardGuests.FORBIDDEN_MESSAGE), messages.toSet(), "wrong, other guest, no header and member row read the same")
        h.forbiddenMessage { h.postService.update(other, "general", mine.post.id, patch) }
        assertEquals("제목", h.store.posts.getValue(mine.post.id).title)
        assertEquals("제목", h.store.posts.getValue(theirs.post.id).title)
        assertEquals("Hello", h.store.posts.getValue(member.id).title)

        h.postService.update(null, "general", mine.post.id, patch, ctx("right-secret"))
        assertEquals("고침", h.store.posts.getValue(mine.post.id).title)
    }

    @Test
    fun `a wrong secret cannot delete and the right one soft deletes`() {
        val h = harness()
        val post = h.guestPost(secret = "right-secret")
        assertEquals(BoardErrorCode.FORBIDDEN, h.failure { h.postService.delete(null, "general", post.post.id, ctx("wrong-secret")) })
        assertEquals(PostStatus.PUBLISHED, h.store.posts.getValue(post.post.id).status)
        h.postService.delete(null, "general", post.post.id, ctx("right-secret"))
        assertEquals(PostStatus.DELETED, h.store.posts.getValue(post.post.id).status)
    }

    @Test
    fun `guest comments are protected the same way and a hidden one cannot be edited`() {
        val h = harness()
        val post = h.newPost().id
        val c = h.guestComment(post, secret = "right-secret").comment
        assertEquals(BoardErrorCode.FORBIDDEN, h.failure { h.commentService.update(null, "general", post, c.id, "고침", ctx("wrong-secret")) })
        assertEquals(BoardErrorCode.FORBIDDEN, h.failure { h.commentService.delete(null, "general", post, c.id, ctx(null)) })
        assertEquals("댓글", h.store.comments.getValue(c.id).body)
        assertEquals("고침", h.commentService.update(null, "general", post, c.id, "고침", ctx("right-secret")).body)
        h.commentService.moderate(moderator, "general", post, c.id, CommentStatus.HIDDEN)
        assertEquals(BoardErrorCode.FORBIDDEN, h.failure { h.commentService.update(null, "general", post, c.id, "또", ctx("right-secret")) })
        h.commentService.moderate(moderator, "general", post, c.id, CommentStatus.PUBLISHED)
        h.commentService.delete(null, "general", post, c.id, ctx("right-secret"))
        assertEquals(CommentStatus.DELETED, h.store.comments.getValue(c.id).status)
    }

    // ---- 대입 · 한도 ----

    @Test
    fun `the eleventh secret attempt on a row is 429 even with the right secret, and the hash is not computed after the limit`() {
        val real = DefaultBoardGuestSecrets(GuestSecretMode.PASSWORD)
        var matches = 0
        val counting = object : BoardGuestSecrets by real {
            override fun matches(raw: String, stored: String): Boolean { matches++; return real.matches(raw, stored) }
        }
        val h = harness(secrets = counting)
        val post = h.guestPost(secret = "right-secret", key = "creator")
        val patch = UpdatePost("고침", null, null, null)
        // 쓰기 한도(5/분)를 피해 시도마다 다른 IP 에서 — 행 한도는 IP 와 무관하게 센다
        repeat(10) { i ->
            assertEquals(BoardErrorCode.FORBIDDEN, h.failure { h.postService.update(null, "general", post.post.id, patch, ctx("wrong-$i-secret", key = "ip-$i")) })
        }
        assertEquals(10, matches)
        assertEquals(BoardErrorCode.RATE_LIMITED, h.failure { h.postService.update(null, "general", post.post.id, patch, ctx("right-secret", key = "ip-new")) })
        assertEquals(10, matches, "past the limit nothing is hashed")
        assertEquals("제목", h.store.posts.getValue(post.post.id).title)
    }

    @Test
    fun `guest writes are limited per address with no rate-limit setting at all`() {
        val h = harness()
        repeat(5) { h.guestPost(key = "same-ip") }
        assertEquals(BoardErrorCode.RATE_LIMITED, h.failure { h.guestPost(key = "same-ip") })
        h.guestPost(key = "other-ip")
    }

    // ---- 누출 ----

    @Test
    fun `neither the secret nor its hash appears in views, events, notices or model strings`() {
        val events = mutableListOf<BoardEvent>()
        val notices = mutableListOf<BoardCommentNotice>()
        val h = harness(listeners = listOf(BoardEventListener { events += it }), notifier = { notices += it })
        val secret = "Sup3r-secret-value"
        val post = h.guestPost(secret = secret)
        val comment = h.guestComment(post.post.id, secret = secret)
        val ownerPost = h.newPost(owner)
        val onMember = h.guestComment(ownerPost.id, secret = secret)
        val hashes = listOf(h.posts.guestSecretHash(post.post.id)!!, h.comments.guestSecretHash(comment.comment.id)!!)
        val text = listOf(
            post.toString(), comment.toString(), onMember.toString(), events.joinToString(), notices.joinToString(),
            h.postService.list(null, "general", PostListRequest(0, 20)).values.joinToString(),
            h.commentService.list(null, "general", post.post.id, 0, 20, CommentSort.OLDEST).values.joinToString(),
            NewPost("general", "guest:x", "t", "b", PostStatus.PUBLISHED, emptyList(), T0, null, "n", hashes[0]).toString(),
            NewComment(1, null, null, 0, "guest:x", "c", T0, guestSecretHash = hashes[1]).toString(),
            ctx(secret).toString(),
        ).joinToString("\n")
        assertFalse(text.contains(secret), "the raw secret leaked")
        hashes.forEach { assertFalse(text.contains(it), "a stored hash leaked") }
    }

    // ---- 캡차 ----

    @Test
    fun `captcha is off by default and members never trigger it`() {
        var calls = 0
        val h = harness(captcha = { _, _, _ -> calls++; false })
        h.guestPost()
        h.newPost(owner)
        assertEquals(0, calls)
    }

    @Test
    fun `with captcha required a guest create needs a valid token and edits and member writes do not call it`() {
        val seen = mutableListOf<String?>()
        val required = BoardProperties(guest = BoardProperties.Guest(enabled = true, captcha = GuestCaptcha.REQUIRED))
        val h = harness(props = required, captcha = { token, _, _ -> seen += token; token == "good" })
        val post = CreatePost("t", "b", null, null, guestName = "x")
        assertEquals(BoardErrorCode.CAPTCHA_FAILED, h.failure { h.postService.create(null, "general", post, ctx(token = null)) })
        assertEquals(BoardErrorCode.CAPTCHA_FAILED, h.failure { h.postService.create(null, "general", post, ctx(token = "bad")) })
        val created = h.postService.create(null, "general", post, ctx(token = "good"))
        val calls = seen.size
        h.postService.update(null, "general", created.post.id, UpdatePost("고침", null, null, null), ctx())
        h.newPost(owner)
        assertEquals(calls, seen.size)
    }

    // ---- 알림 · 이벤트 ----

    @Test
    fun `a guest comment notifies a member post owner with the guest public id, and nobody notifies a guest`() {
        val notices = mutableListOf<BoardCommentNotice>()
        val h = harness(notifier = { notices += it })
        val memberPost = h.newPost(owner)
        val guestOnMember = h.guestComment(memberPost.id)
        val notice = notices.single()
        assertEquals("acc_author", notice.recipientId)
        assertEquals(guestOnMember.comment.authorId, notice.publicAuthorId)
        assertEquals("손님", notice.authorName)

        notices.clear()
        val guestPost = h.guestPost()
        val memberOnGuest = h.commentService.create(other, "general", guestPost.post.id, null, "답")
        h.commentService.create(owner, "general", guestPost.post.id, memberOnGuest.comment.id, "또")
        h.commentService.create(owner, "general", memberPost.id, guestOnMember.comment.id, "guest 댓글에 답")
        assertEquals(listOf("acc_other"), notices.map { it.recipientId }, "guest recipients are skipped")
    }

    @Test
    fun `guest writes publish events with the GUEST actor`() {
        val events = mutableListOf<BoardEvent>()
        val h = harness(listeners = listOf(BoardEventListener { events += it }))
        val post = h.guestPost()
        h.guestComment(post.post.id)
        assertEquals(listOf(BoardActorKind.GUEST, BoardActorKind.GUEST), events.map { it.actor.kind })
        assertNull(events.first().actor.accountId)
    }

    private fun ServiceHarness.forbiddenMessage(block: () -> Unit): String {
        val e = assertFailsWith<BoardException> { block() }
        assertEquals(BoardErrorCode.FORBIDDEN, e.errorCode)
        return e.message.orEmpty()
    }
}
