package dev.sumin.skeleton.board

import dev.sumin.skeleton.common.author.AuthorContext
import dev.sumin.skeleton.common.author.AuthorIdentities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 작성자 프로필의 쓰기 쪽(스펙 2026-10-07 §7.1 · §7.8): 네 줄 규칙, author_ref 불변, 첨부 막기, config. 가짜 [AuthorIdentities] + 메모리 저장소 */
class BoardAuthorWritesTest {
    private val ids = standardIdentities()

    @Test
    fun `no identities and no authorId - as before, author_ref stays null and the public id is the account id`() {
        val view = authorHarness(null).post(owner, null)
        assertNull(view.post.authorRef)
        assertEquals("acc_author", view.publicAuthorId)
    }

    @Test
    fun `no identities but an authorId is sent - 403 AUTHOR_NOT_ALLOWED and nothing is written`() {
        val h = authorHarness(null)
        assertEquals(BoardErrorCode.AUTHOR_NOT_ALLOWED, h.failure { h.post(owner, "prf_a1") })
        assertTrue(h.store.posts.isEmpty())
    }

    @Test
    fun `identities present and no authorId - 400 AUTHOR_REQUIRED`() {
        val h = authorHarness(ids)
        assertEquals(BoardErrorCode.AUTHOR_REQUIRED, h.failure { h.post(owner, null) })
        assertEquals(BoardErrorCode.AUTHOR_REQUIRED, h.failure { h.post(owner, "  ") }, "blank counts as missing")
        assertTrue(h.store.posts.isEmpty())
    }

    @Test
    fun `identities present and an allowed authorId - author_ref is that id, author_id is the caller account, the board code is the context`() {
        val view = authorHarness(ids).post(owner, "prf_a1")
        assertEquals("prf_a1", view.post.authorRef)
        assertEquals("acc_author", view.post.authorId)
        assertEquals("prf_a1", view.publicAuthorId)
        assertEquals(Triple("acc_author", "prf_a1", AuthorContext("board", "general")), ids.asked.single())
    }

    @Test
    fun `a refused authorId is 403 AUTHOR_NOT_ALLOWED with the same words whatever the reason - and with or without the SPI bean`() {
        val h = authorHarness(ids)
        assertEquals(BoardErrorCode.AUTHOR_NOT_ALLOWED, h.failure { h.post(owner, "prf_o1") })       // 남의 id
        assertEquals(BoardErrorCode.AUTHOR_NOT_ALLOWED, h.failure { h.post(owner, "prf_nope") })     // 없는 id
        val messages = listOf("prf_o1", "prf_nope").map { id -> failureMessage { h.post(owner, id) } }.toMutableList()
        val bare = authorHarness(null)
        messages += failureMessage { bare.post(owner, "prf_a1") }
        assertEquals(1, messages.toSet().size, "the message must not tell the reasons apart: $messages")
        assertFalse(messages.first().contains("prf_"), "the message does not echo the id")
        assertTrue(h.store.posts.isEmpty())
    }

    @Test
    fun `an authorId longer than the author_ref column is refused without asking the SPI`() {
        val h = authorHarness(ids)
        assertEquals(BoardErrorCode.AUTHOR_NOT_ALLOWED, h.failure { h.post(owner, "prf_" + "x".repeat(61)) })
        assertTrue(ids.asked.isEmpty(), "short-circuited before allows()")
    }

    @Test
    fun `comments follow the same four rules`() {
        val none = authorHarness(null)
        val post = none.newPost(owner).id
        assertNull(none.commentService.create(other, "general", post, null, "hi").comment.authorRef)
        assertEquals(BoardErrorCode.AUTHOR_NOT_ALLOWED, none.failure { none.commentService.create(other, "general", post, null, "hi", "prf_o1") })

        val h = authorHarness(ids)
        val p = h.post(owner, "prf_a1").post.id
        assertEquals(BoardErrorCode.AUTHOR_REQUIRED, h.failure { h.commentService.create(other, "general", p, null, "hi") })
        assertEquals(BoardErrorCode.AUTHOR_NOT_ALLOWED, h.failure { h.commentService.create(other, "general", p, null, "hi", "prf_a1") })
        val made = h.commentService.create(other, "general", p, null, "hi", "prf_o1")
        assertEquals("prf_o1", made.comment.authorRef)
        assertEquals("acc_other", made.comment.authorId)
        assertEquals("prf_o1", made.publicAuthorId)
    }

    @Test
    fun `a refused author never reaches the write limiter and inserts nothing`() {
        val limiter = CountingLimiter()
        val h = authorHarness(ids, limiter = limiter)
        val p = h.post(owner, "prf_a1").post.id
        val before = limiter.calls
        h.failure { h.post(owner, "prf_o1") }
        h.failure { h.post(owner, null) }
        h.failure { h.commentService.create(other, "general", p, null, "hi", "prf_a1") }
        h.failure { h.commentService.create(other, "general", p, null, "hi") }
        assertEquals(before, limiter.calls, "no limiter call for a refused author")
        assertTrue(h.store.comments.isEmpty())
        assertEquals(1, h.store.posts.size)
    }

    @Test
    fun `editing never changes author_ref - not by the author, not by a moderator, not through a DRAFT round trip`() {
        val h = authorHarness(ids)
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

    @Test
    fun `with identities an attachment key holding the caller's account id is refused as invalid content`() {
        val h = authorHarness(ids)
        val bad = listOf("uploads/acc_author/0b9e/photo.png")
        assertEquals(BoardErrorCode.CONTENT_INVALID, h.failure { h.post(owner, "prf_a1", attachments = bad) })
        assertEquals(BoardErrorCode.CONTENT_INVALID, h.failure { h.post(owner, "prf_a1", attachments = listOf("fine.png") + bad) })
        val made = h.post(owner, "prf_a1", attachments = listOf("uploads/o/xxxx.mac/photo.png"))
        assertEquals(BoardErrorCode.CONTENT_INVALID, h.failure { h.postService.update(owner, "general", made.post.id, UpdatePost(null, null, bad, null)) })
    }

    @Test
    fun `an edit that resends keys the post already holds passes - only newly attached keys are checked`() {
        val legacy = "uploads/acc_author/0b9e/photo.png"
        val h = authorHarness(ids)
        val old = h.posts.insert(NewPost("general", "acc_author", "t", "b", PostStatus.PUBLISHED, listOf(legacy), T0, authorRef = "prf_a1")).id
        val kept = h.postService.update(owner, "general", old, UpdatePost("renamed", null, listOf(legacy), null))
        assertEquals(listOf(legacy), kept.post.attachments)
        assertEquals(
            BoardErrorCode.CONTENT_INVALID,
            h.failure { h.postService.update(owner, "general", old, UpdatePost(null, null, listOf(legacy, "uploads/acc_author/new/other.png"), null)) },
            "a new key with the account id is still refused next to an old one",
        )
    }

    @Test
    fun `another account's id in a key is not this check's business, and without identities nothing is refused`() {
        authorHarness(ids).post(owner, "prf_a1", attachments = listOf("uploads/acc_other/x/photo.png"))
        authorHarness(null).post(owner, null, attachments = listOf("uploads/acc_author/x/photo.png"))
    }

    @Test
    fun `the config says whether a write needs an author`() {
        assertTrue(authorHarness(ids).boardService.config(owner).authorRequired)
        assertFalse(authorHarness(null).boardService.config(owner).authorRequired)
        assertFalse(authorHarness(null).boardService.config(null).authorRequired)
    }
}
