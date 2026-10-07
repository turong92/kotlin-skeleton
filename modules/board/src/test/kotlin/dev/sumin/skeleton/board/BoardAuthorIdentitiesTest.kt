package dev.sumin.skeleton.board

import dev.sumin.skeleton.common.author.AuthorCard
import dev.sumin.skeleton.common.author.AuthorContext
import dev.sumin.skeleton.common.author.AuthorDirectory
import dev.sumin.skeleton.common.author.AuthorIdentities
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 작성자 프로필(스펙 2026-10-07 §7.1 · §7.2): 공개 작성자 id 한 곳, 쓰기 규칙 네 줄, `mine`, 알림, 첨부.
 * 가짜 [AuthorIdentities] 와 메모리 저장소로 — 진짜 DB 는 board-jdbc 의 dbTest 가 본다.
 */
class BoardAuthorIdentitiesTest {
    /** 계정 → 쓸 수 있는 공개 id 들. 부른 인자를 남긴다 */
    private class Identities(val allowed: Map<String, Set<String>>) : AuthorIdentities {
        val asked = CopyOnWriteArrayList<Triple<String, String, AuthorContext>>()
        override fun allows(accountId: String, authorId: String, context: AuthorContext): Boolean {
            asked += Triple(accountId, authorId, context)
            return authorId in allowed[accountId].orEmpty()
        }
    }

    private val ids = Identities(mapOf("acc_author" to setOf("prf_a1", "prf_a2"), "acc_other" to setOf("prf_o1")))

    private fun on(identities: AuthorIdentities? = ids, notices: MutableList<BoardCommentNotice> = mutableListOf(), directory: AuthorDirectory = AuthorDirectory.NONE) =
        ServiceHarness(identities = identities, directory = directory, notifier = { notices += it })

    private fun ServiceHarness.post(who: BoardCaller, authorId: String?, status: PostStatus? = null) =
        postService.create(who, "general", CreatePost("t", "b", null, status, authorId))

    // ---- §7.1 쓰기 규칙 네 줄 (글)

    @Test
    fun `no identities and no authorId - as before, author_ref stays null and the public id is the account id`() {
        val h = on(identities = null)
        val view = h.post(owner, null)
        assertNull(view.post.authorRef)
        assertEquals("acc_author", view.publicAuthorId)
    }

    @Test
    fun `no identities but an authorId is sent - 403 AUTHOR_NOT_ALLOWED and nothing is written`() {
        val h = on(identities = null)
        assertEquals(BoardErrorCode.AUTHOR_NOT_ALLOWED, h.failure { h.post(owner, "prf_a1") })
        assertTrue(h.store.posts.isEmpty())
    }

    @Test
    fun `identities present and no authorId - 400 AUTHOR_REQUIRED`() {
        val h = on()
        assertEquals(BoardErrorCode.AUTHOR_REQUIRED, h.failure { h.post(owner, null) })
        assertEquals(BoardErrorCode.AUTHOR_REQUIRED, h.failure { h.post(owner, "  ") }, "blank counts as missing")
        assertTrue(h.store.posts.isEmpty())
    }

    @Test
    fun `identities present and an allowed authorId - author_ref is that id, author_id is still the caller account, and the board code is the context`() {
        val h = on()
        val view = h.post(owner, "prf_a1")
        assertEquals("prf_a1", view.post.authorRef)
        assertEquals("acc_author", view.post.authorId)
        assertEquals("prf_a1", view.publicAuthorId)
        assertEquals(Triple("acc_author", "prf_a1", AuthorContext("board", "general")), ids.asked.single())
    }

    @Test
    fun `identities present and a refused authorId - 403 AUTHOR_NOT_ALLOWED with the same words whatever the reason`() {
        val h = on()
        val others = h.failure { h.post(owner, "prf_o1") }              // 남의 id
        val unknown = h.failure { h.post(owner, "prf_nope") }           // 없는 id
        assertEquals(BoardErrorCode.AUTHOR_NOT_ALLOWED, others)
        assertEquals(BoardErrorCode.AUTHOR_NOT_ALLOWED, unknown)
        val messages = listOf("prf_o1", "prf_nope").map { id ->
            assertFailsMessage { h.post(owner, id) }
        }
        assertEquals(1, messages.toSet().size, "the message must not tell the reasons apart: $messages")
        assertFalse(messages.first().contains("prf_"), "the message does not echo the id")
        assertTrue(h.store.posts.isEmpty())
    }

    // ---- §7.1 쓰기 규칙 네 줄 (댓글)

    @Test
    fun `comments follow the same four rules`() {
        val none = on(identities = null)
        val post = none.newPost(owner).id
        assertNull(none.commentService.create(other, "general", post, null, "hi").comment.authorRef)
        assertEquals(BoardErrorCode.AUTHOR_NOT_ALLOWED, none.failure { none.commentService.create(other, "general", post, null, "hi", "prf_o1") })

        val h = on()
        val p = h.post(owner, "prf_a1").post.id
        assertEquals(BoardErrorCode.AUTHOR_REQUIRED, h.failure { h.commentService.create(other, "general", p, null, "hi") })
        assertEquals(BoardErrorCode.AUTHOR_NOT_ALLOWED, h.failure { h.commentService.create(other, "general", p, null, "hi", "prf_a1") })
        val made = h.commentService.create(other, "general", p, null, "hi", "prf_o1")
        assertEquals("prf_o1", made.comment.authorRef)
        assertEquals("acc_other", made.comment.authorId)
        assertEquals("prf_o1", made.publicAuthorId)
        assertEquals(0, h.store.comments.values.count { it.authorRef == null })
    }

    @Test
    fun `a refused author does not spend the write limit or insert a row`() {
        val h = on()
        val p = h.post(owner, "prf_a1").post.id
        h.failure { h.commentService.create(other, "general", p, null, "hi", "prf_a1") }
        assertTrue(h.store.comments.isEmpty())
        assertEquals(0, h.store.posts.getValue(p).commentCount)
    }

    // ---- 수정으로 author_ref 불변

    @Test
    fun `editing never changes author_ref - not by the author, not by a moderator, not through a DRAFT round trip`() {
        val h = on()
        val post = h.post(owner, "prf_a1").post.id
        h.postService.update(owner, "general", post, UpdatePost("new", null, null, PostStatus.DRAFT))
        assertEquals("prf_a1", h.store.posts.getValue(post).authorRef)
        h.postService.update(owner, "general", post, UpdatePost(null, "body 2", null, PostStatus.PUBLISHED))
        assertEquals("prf_a1", h.store.posts.getValue(post).authorRef)
        h.postService.update(moderator, "general", post, UpdatePost("by mod", null, null, null))
        assertEquals("prf_a1", h.store.posts.getValue(post).authorRef)
        h.postService.moderate(moderator, "general", post, PostStatus.HIDDEN, true)
        assertEquals("prf_a1", h.store.posts.getValue(post).authorRef)

        h.postService.moderate(moderator, "general", post, PostStatus.PUBLISHED, null)
        val comment = h.commentService.create(other, "general", post, null, "c", "prf_o1").comment.id
        h.commentService.update(other, "general", post, comment, "edited")
        assertEquals("prf_o1", h.store.comments.getValue(comment).authorRef)
        assertEquals("acc_other", h.store.comments.getValue(comment).authorId)
    }

    // ---- §7.2 공개 id 한 곳

    @Test
    fun `with identities no view carries author_id - a legacy row is pending, a row with author_ref shows it`() {
        val h = on()
        val legacyPost = h.posts.insert(NewPost("general", "acc_old", "old", "b", PostStatus.PUBLISHED, emptyList(), T0)).id
        val legacyComment = h.comments.insert(NewComment(legacyPost, null, null, 0, "acc_old", "c", T0))!!.id
        val fresh = h.post(owner, "prf_a1").post.id

        val list = h.postService.list(null, "general", PostListRequest(0, 20)).values.associateBy { it.item.id }
        assertEquals("pending:p$legacyPost", list.getValue(legacyPost).publicAuthorId)
        assertEquals("prf_a1", list.getValue(fresh).publicAuthorId)
        assertEquals("pending:p$legacyPost", h.postService.get(null, "general", legacyPost).publicAuthorId)
        val threads = h.commentService.list(null, "general", legacyPost, 0, 20, CommentSort.OLDEST).values
        assertEquals("pending:c$legacyComment", threads.single().publicAuthorId)
        assertFalse(listOf(list.values.map { it.publicAuthorId }, threads.map { it.publicAuthorId }).flatten().any { it.contains("acc_") })
    }

    @Test
    fun `without identities the public id is author_ref when present, else the account id`() {
        val h = on(identities = null)
        val withRef = h.posts.insert(NewPost("general", "acc_a", "t", "b", PostStatus.PUBLISHED, emptyList(), T0, authorRef = "prf_kept")).id
        val without = h.posts.insert(NewPost("general", "acc_a", "t", "b", PostStatus.PUBLISHED, emptyList(), T0)).id
        assertEquals("prf_kept", h.postService.get(null, "general", withRef).publicAuthorId)
        assertEquals("acc_a", h.postService.get(null, "general", without).publicAuthorId)
    }

    @Test
    fun `a tombstoned row shows author_ref when it has one, otherwise the tombstone itself - and is authorDeleted either way`() {
        val h = on()
        val withRef = h.posts.insert(NewPost("general", "deleted:aaaaaaaaaaaaaaaa", "t", "b", PostStatus.PUBLISHED, emptyList(), T0, authorRef = "prf_a1")).id
        val legacy = h.posts.insert(NewPost("general", "deleted:aaaaaaaaaaaaaaaa", "t", "b", PostStatus.PUBLISHED, emptyList(), T0)).id
        val a = h.postService.get(null, "general", withRef)
        val b = h.postService.get(null, "general", legacy)
        assertEquals("prf_a1", a.publicAuthorId)
        assertTrue(a.authorDeleted)
        assertEquals("deleted:aaaaaaaaaaaaaaaa", b.publicAuthorId)
        assertTrue(b.authorDeleted)
        assertFalse(a.mine || b.mine)
    }

    @Test
    fun `authorDeleted is also true when the card says the profile is deleted, and the card's name is null then`() {
        val h = on(directory = { ids, _ -> ids.associateWith { if (it == "prf_gone") AuthorCard(null, null, deleted = true) else AuthorCard("Nick", "0001") } })
        h.posts.insert(NewPost("general", "acc_x", "t", "b", PostStatus.PUBLISHED, emptyList(), T0, authorRef = "prf_gone"))
        h.posts.insert(NewPost("general", "acc_x", "t", "b", PostStatus.PUBLISHED, emptyList(), T0, authorRef = "prf_live"))
        val rows = h.postService.list(null, "general", PostListRequest(0, 20)).values.associateBy { it.publicAuthorId }
        assertTrue(rows.getValue("prf_gone").authorDeleted)
        assertNull(rows.getValue("prf_gone").author!!.name)
        assertFalse(rows.getValue("prf_live").authorDeleted)
        assertEquals("Nick", rows.getValue("prf_live").author!!.name)
    }

    @Test
    fun `names are asked by public id - never by account id, never for tombstones or pending rows`() {
        val asked = CopyOnWriteArrayList<List<String>>()
        val h = on(directory = { ids, _ -> asked += ids.toList(); emptyMap() })
        h.posts.insert(NewPost("general", "acc_old", "t", "b", PostStatus.PUBLISHED, emptyList(), T0))
        h.posts.insert(NewPost("general", "deleted:aaaaaaaaaaaaaaaa", "t", "b", PostStatus.PUBLISHED, emptyList(), T0))
        h.post(owner, "prf_a1")
        asked.clear()
        h.postService.list(null, "general", PostListRequest(0, 20))
        assertEquals(listOf(listOf("prf_a1")), asked.toList())
    }

    // ---- mine

    @Test
    fun `mine is the account comparison - with and without identities, in lists, details and comments`() {
        for (identities in listOf<AuthorIdentities?>(ids, null)) {
            val h = on(identities = identities)
            val p = if (identities == null) h.post(owner, null) else h.post(owner, "prf_a1")
            val c = if (identities == null) h.commentService.create(other, "general", p.post.id, null, "hi")
            else h.commentService.create(other, "general", p.post.id, null, "hi", "prf_o1")
            assertTrue(h.postService.get(owner, "general", p.post.id).mine)
            assertTrue(h.postService.list(owner, "general", PostListRequest(0, 20)).values.single().mine)
            assertFalse(h.postService.get(other, "general", p.post.id).mine)
            assertFalse(h.postService.get(null, "general", p.post.id).mine, "anonymous is nobody's")
            assertFalse(h.postService.get(moderator, "general", p.post.id).mine, "a moderator is not the author")
            val thread = h.commentService.list(other, "general", p.post.id, 0, 20, CommentSort.OLDEST).values.single()
            assertTrue(thread.mine)
            assertFalse(h.commentService.list(owner, "general", p.post.id, 0, 20, CommentSort.OLDEST).values.single().mine)
            assertTrue(c.mine)
        }
    }

    @Test
    fun `an account that writes under two profiles owns both`() {
        val h = on()
        val a = h.post(owner, "prf_a1").post.id
        val b = h.post(owner, "prf_a2").post.id
        val mine = h.postService.list(owner, "general", PostListRequest(0, 20, mine = true)).values
        assertEquals(setOf(a, b), mine.map { it.item.id }.toSet())
        assertTrue(mine.all { it.mine })
        assertEquals(setOf("prf_a1", "prf_a2"), mine.map { it.publicAuthorId }.toSet())
    }

    // ---- 알림

    @Test
    fun `the notice carries the public author id, the recipient stays an account`() {
        val notices = mutableListOf<BoardCommentNotice>()
        val h = on(notices = notices)
        val post = h.post(owner, "prf_a1").post.id
        h.commentService.create(other, "general", post, null, "hi", "prf_o1")
        val n = notices.single()
        assertEquals("acc_author", n.recipientId)
        assertEquals("prf_o1", n.publicAuthorId)
    }

    @Test
    fun `a legacy commenter's notice does not carry the account id either`() {
        val notices = mutableListOf<BoardCommentNotice>()
        val h = on(identities = null, notices = notices)
        val post = h.newPost(owner).id
        h.commentService.create(other, "general", post, null, "hi")
        assertEquals("acc_other", notices.single().publicAuthorId, "no identities: the account id is the public id, as before")
    }

    @Test
    fun `no self notification by account - answering your own post or comment under another profile stays quiet`() {
        val notices = mutableListOf<BoardCommentNotice>()
        val h = on(notices = notices)
        val post = h.post(owner, "prf_a1").post.id
        val root = h.commentService.create(owner, "general", post, null, "me again", "prf_a2").comment.id
        assertTrue(notices.isEmpty(), "same account, other profile: no alert")
        h.commentService.create(other, "general", post, root, "reply", "prf_o1")
        assertEquals(listOf("acc_author"), notices.map { it.recipientId })
        h.commentService.create(owner, "general", post, root, "answer to myself", "prf_a1")
        assertEquals(1, notices.size, "answering your own comment under another profile is still your own - no alert")
    }

    // ---- 첨부

    @Test
    fun `with identities an attachment key holding the caller's account id is refused as invalid content`() {
        val h = on()
        val bad = listOf("uploads/acc_author/0b9e/photo.png")
        assertEquals(BoardErrorCode.CONTENT_INVALID, h.failure { h.postService.create(owner, "general", CreatePost("t", "b", bad, null, "prf_a1")) })
        val made = h.postService.create(owner, "general", CreatePost("t", "b", listOf("uploads/o/xxxx.mac/photo.png"), null, "prf_a1"))
        assertEquals(1, made.post.attachments.size)
        assertEquals(BoardErrorCode.CONTENT_INVALID, h.failure { h.postService.update(owner, "general", made.post.id, UpdatePost(null, null, bad, null)) })
        assertEquals(BoardErrorCode.CONTENT_INVALID, h.failure { h.postService.create(owner, "general", CreatePost("t", "b", listOf("fine.png") + bad, null, "prf_a1")) })
    }

    @Test
    fun `another account's id in a key is not this check's business, and without identities nothing is refused`() {
        val h = on()
        h.postService.create(owner, "general", CreatePost("t", "b", listOf("uploads/acc_other/x/photo.png"), null, "prf_a1"))
        val old = on(identities = null)
        old.postService.create(owner, "general", CreatePost("t", "b", listOf("uploads/acc_author/x/photo.png"), null))
    }

    // ---- config

    @Test
    fun `the config says whether a write needs an author`() {
        assertTrue(on().boardService.config(owner).authorRequired)
        assertFalse(on(identities = null).boardService.config(owner).authorRequired)
        assertFalse(on(identities = null).boardService.config(null).authorRequired)
    }

    private fun assertFailsMessage(block: () -> Unit): String = kotlin.test.assertFailsWith<BoardException> { block() }.message.orEmpty()
}
