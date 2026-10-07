package dev.sumin.skeleton.board

import dev.sumin.skeleton.common.author.AuthorCard
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 작성자 프로필의 읽기 쪽(스펙 2026-10-07 §7.2): 공개 id 한 곳 · `authorDeleted` · `mine` · 알림 payload · 자기 알림 생략 */
class BoardAuthorReadsTest {
    private val tombstone = "deleted:aaaaaaaaaaaaaaaa"

    private fun ServiceHarness.legacyPost(author: String = "acc_old", ref: String? = null) =
        posts.insert(NewPost("general", author, "t", "b", PostStatus.PUBLISHED, emptyList(), T0, authorRef = ref)).id

    @Test
    fun `with identities no view carries author_id - a legacy row is pending, a row with author_ref shows it`() {
        val h = authorHarness()
        val legacyPost = h.legacyPost()
        val legacyComment = h.comments.insert(NewComment(legacyPost, null, null, 0, "acc_old", "c", T0))!!.id
        val fresh = h.post(owner, "prf_a1").post.id

        val list = h.postService.list(null, "general", PostListRequest(0, 20)).values.associateBy { it.item.id }
        assertEquals("pending:p$legacyPost", list.getValue(legacyPost).publicAuthorId)
        assertEquals("prf_a1", list.getValue(fresh).publicAuthorId)
        assertEquals("pending:p$legacyPost", h.postService.get(null, "general", legacyPost).publicAuthorId)
        val threads = h.commentService.list(null, "general", legacyPost, 0, 20, CommentSort.OLDEST).values
        assertEquals("pending:c$legacyComment", threads.single().publicAuthorId)
        assertFalse((list.values.map { it.publicAuthorId } + threads.map { it.publicAuthorId }).any { it.contains("acc_") })
    }

    @Test
    fun `without identities the public id is author_ref when present, else the account id - and a tombstone stays as it was`() {
        val h = authorHarness(null)
        val withRef = h.legacyPost("acc_a", "prf_kept")
        val without = h.legacyPost("acc_a")
        val gone = h.legacyPost(tombstone)
        assertEquals("prf_kept", h.postService.get(null, "general", withRef).publicAuthorId)
        assertEquals("acc_a", h.postService.get(null, "general", without).publicAuthorId)
        assertEquals(tombstone, h.postService.get(null, "general", gone).publicAuthorId)
    }

    @Test
    fun `with identities a tombstoned row shows author_ref when it has one, otherwise pending - never the tombstone - and is authorDeleted either way`() {
        val h = authorHarness()
        val withRef = h.legacyPost(tombstone, "prf_a1")
        val legacy = h.legacyPost(tombstone)
        val a = h.postService.get(null, "general", withRef)
        val b = h.postService.get(null, "general", legacy)
        assertEquals("prf_a1", a.publicAuthorId)
        assertEquals("pending:p$legacy", b.publicAuthorId)
        assertTrue(a.authorDeleted && b.authorDeleted)
        assertFalse(a.mine || b.mine)
    }

    @Test
    fun `authorDeleted is also true when the card says the profile is deleted, and the card's name is null then`() {
        val h = authorHarness(directory = { ids, _ -> ids.associateWith { if (it == "prf_gone") AuthorCard(null, null, deleted = true) else AuthorCard("Nick", "0001") } })
        h.legacyPost("acc_x", "prf_gone")
        h.legacyPost("acc_x", "prf_live")
        val rows = h.postService.list(null, "general", PostListRequest(0, 20)).values.associateBy { it.publicAuthorId }
        assertTrue(rows.getValue("prf_gone").authorDeleted)
        assertNull(rows.getValue("prf_gone").author!!.name)
        assertFalse(rows.getValue("prf_live").authorDeleted)
        assertEquals("Nick", rows.getValue("prf_live").author!!.name)
    }

    @Test
    fun `names are asked by public id - never by account id, never for tombstones or pending rows`() {
        val asked = CopyOnWriteArrayList<List<String>>()
        val h = authorHarness(directory = { ids, _ -> asked += ids.toList(); emptyMap() })
        h.legacyPost()
        h.legacyPost(tombstone)
        h.post(owner, "prf_a1")
        asked.clear()
        h.postService.list(null, "general", PostListRequest(0, 20))
        assertEquals(listOf(listOf("prf_a1")), asked.toList())
    }

    @Test
    fun `mine is the account comparison - with and without identities, in lists, details and comments`() {
        for (identities in listOf(standardIdentities(), null)) {
            val h = authorHarness(identities)
            val p = h.post(owner, identities?.let { "prf_a1" })
            val c = h.commentService.create(other, "general", p.post.id, null, "hi", identities?.let { "prf_o1" })
            assertTrue(h.postService.get(owner, "general", p.post.id).mine)
            assertTrue(h.postService.list(owner, "general", PostListRequest(0, 20)).values.single().mine)
            assertFalse(h.postService.get(other, "general", p.post.id).mine)
            assertFalse(h.postService.get(null, "general", p.post.id).mine, "anonymous is nobody's")
            assertFalse(h.postService.get(moderator, "general", p.post.id).mine, "a moderator is not the author")
            assertTrue(h.commentService.list(other, "general", p.post.id, 0, 20, CommentSort.OLDEST).values.single().mine)
            assertFalse(h.commentService.list(owner, "general", p.post.id, 0, 20, CommentSort.OLDEST).values.single().mine)
            assertTrue(c.mine)
        }
    }

    @Test
    fun `an account that writes under two profiles owns both`() {
        val h = authorHarness()
        val a = h.post(owner, "prf_a1").post.id
        val b = h.post(owner, "prf_a2").post.id
        val mine = h.postService.list(owner, "general", PostListRequest(0, 20, mine = true)).values
        assertEquals(setOf(a, b), mine.map { it.item.id }.toSet())
        assertTrue(mine.all { it.mine })
        assertEquals(setOf("prf_a1", "prf_a2"), mine.map { it.publicAuthorId }.toSet())
    }

    @Test
    fun `the notice carries the public author id, the recipient stays an account`() {
        val notices = mutableListOf<BoardCommentNotice>()
        val h = authorHarness(notices = notices)
        val post = h.post(owner, "prf_a1").post.id
        h.commentService.create(other, "general", post, null, "hi", "prf_o1")
        assertEquals("acc_author", notices.single().recipientId)
        assertEquals("prf_o1", notices.single().publicAuthorId)
    }

    @Test
    fun `without identities the notice carries the account id as before`() {
        val notices = mutableListOf<BoardCommentNotice>()
        val h = authorHarness(null, notices = notices)
        h.commentService.create(other, "general", h.newPost(owner).id, null, "hi")
        assertEquals("acc_other", notices.single().publicAuthorId)
    }

    @Test
    fun `with identities a comment row without author_ref is announced as pending, never by its account id`() {
        val notices = mutableListOf<BoardCommentNotice>()
        // 저장소가 author_ref 를 남기지 않는 경우(옛 행과 같은 모양)를 흉내 낸다
        val h = authorHarness(notices = notices, wrapComments = { real ->
            object : CommentRepository by real {
                override fun insert(comment: NewComment) = real.insert(comment.copy(authorRef = null))
            }
        })
        val post = h.post(owner, "prf_a1").post.id
        val made = h.commentService.create(other, "general", post, null, "hi", "prf_o1")
        assertEquals("pending:c${made.comment.id}", notices.single().publicAuthorId)
        assertFalse(notices.single().publicAuthorId.contains("acc_"))
    }

    @Test
    fun `no self notification by account - answering your own post or comment under another profile stays quiet`() {
        val notices = mutableListOf<BoardCommentNotice>()
        val h = authorHarness(notices = notices)
        val post = h.post(owner, "prf_a1").post.id
        val root = h.commentService.create(owner, "general", post, null, "me again", "prf_a2").comment.id
        assertTrue(notices.isEmpty(), "same account, other profile: no alert")
        h.commentService.create(other, "general", post, root, "reply", "prf_o1")
        assertEquals(listOf("acc_author"), notices.map { it.recipientId })
        h.commentService.create(owner, "general", post, root, "answer to myself", "prf_a1")
        assertEquals(1, notices.size, "answering your own comment under another profile is still your own - no alert")
    }
}
