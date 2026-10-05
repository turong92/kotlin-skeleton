package dev.sumin.skeleton.board.jdbc

import dev.sumin.skeleton.board.PostChange
import dev.sumin.skeleton.board.PostQuery
import dev.sumin.skeleton.board.PostSort
import dev.sumin.skeleton.board.PostStatus
import dev.sumin.skeleton.board.ReactionMode
import dev.sumin.skeleton.board.ReactionTarget
import dev.sumin.skeleton.board.jdbc.BoardDb.T0
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JdbcBoardAndPostDbTest {
    private val db = BoardDb

    @Test
    fun `a board is created once and listed with its published post count`() {
        val code = "bd-${System.nanoTime() % 1000000}"
        assertTrue(db.boards.create(code, "Name", "desc", T0))
        assertFalse(db.boards.create(code, "Other", null, T0))
        db.newPost(code); db.newPost(code); db.newPost(code, status = PostStatus.DRAFT)
        val board = db.boards.find(code)!!
        assertEquals(listOf("Name", "desc", 2L, T0), listOf(board.name, board.description, board.postCount, board.createdAt))
        assertEquals(2L, db.boards.findAll().first { it.code == code }.postCount)
        assertNull(db.boards.find("no-such-board"))
    }

    @Test
    fun `parallel creation of the same board code succeeds exactly once`() {
        val code = "race-${System.nanoTime() % 1000000}"
        val results = parallel(16) { _ -> db.boards.create(code, "x", null, T0) }
        assertEquals(1, results.count { it })
    }

    @Test
    fun `a post round-trips with attachments in order and microsecond timestamps`() {
        val board = db.newBoard()
        val saved = db.newPost(board, title = "T", body = "B\nmulti-line 한글 😀", attachments = listOf("k/2", "k/1"))
        val found = db.posts.find(saved.id)!!
        assertEquals(saved, found)
        assertEquals(listOf("k/2", "k/1"), found.attachments)
        assertEquals("B\nmulti-line 한글 😀", found.body)
        assertEquals(T0, found.createdAt)
        assertEquals(listOf(PostStatus.PUBLISHED, false, 0L, 0L, 0L), listOf(found.status, found.pinned, found.viewCount, found.commentCount, found.reactionCount))
        assertNull(db.posts.find(-1))
    }

    @Test
    fun `update changes only the given fields and replaces attachments`() {
        val board = db.newBoard()
        val p = db.newPost(board, attachments = listOf("a"))
        val later = T0.plus(Duration.ofMinutes(5))
        val u = db.posts.update(p.id, PostChange(title = "New", attachments = listOf("b", "c"), pinned = true), later)!!
        assertEquals(listOf("New", "body", true, listOf("b", "c"), later, T0), listOf(u.title, u.body, u.pinned, u.attachments, u.updatedAt, u.createdAt))
        val cleared = db.posts.update(p.id, PostChange(attachments = emptyList(), status = PostStatus.HIDDEN), later)!!
        assertEquals(emptyList<String>(), cleared.attachments)
        assertEquals(PostStatus.HIDDEN, cleared.status)
        assertNull(db.posts.update(-1, PostChange(title = "x"), later))
    }

    @Test
    fun `views count exactly under parallel increments`() {
        val p = db.newPost(db.newBoard())
        parallel(40) { _ -> db.posts.incrementViews(p.id) }
        assertEquals(40L, db.posts.find(p.id)!!.viewCount)
    }

    private fun query(board: String, sort: PostSort = PostSort.LATEST, page: Int = 0, size: Int = 20, q: String? = null, reaction: String? = null,
                      statuses: Set<PostStatus> = setOf(PostStatus.PUBLISHED), author: String? = null, excerpt: Int = 140) =
        PostQuery(board, page, size, sort, reaction, q, statuses, author, excerpt)

    @Test
    fun `the list is newest first with pinned posts on top, paged with a total`() {
        val board = db.newBoard()
        val a = db.newPost(board, title = "a", at = T0)
        val b = db.newPost(board, title = "b", at = T0.plusSeconds(10))
        val c = db.newPost(board, title = "c", at = T0.plusSeconds(20))
        db.posts.update(a.id, PostChange(pinned = true), T0)

        val all = db.posts.page(query(board))
        assertEquals(listOf(a.id, c.id, b.id), all.values.map { it.id })
        assertEquals(3, all.totalElements)
        val second = db.posts.page(query(board, page = 1, size = 2))
        assertEquals(listOf(b.id), second.values.map { it.id })
        assertEquals(3, second.totalElements)
        assertEquals(PostStatus.PUBLISHED, all.values.first().status)
        assertTrue(all.values.first().pinned)
    }

    @Test
    fun `status and author filters, and the excerpt is cut in SQL`() {
        val board = db.newBoard()
        db.newPost(board, author = "x", body = "0123456789")
        db.newPost(board, author = "y", status = PostStatus.DRAFT)
        db.newPost(board, author = "y", status = PostStatus.HIDDEN)
        assertEquals(1, db.posts.page(query(board)).totalElements)
        assertEquals(2, db.posts.page(query(board, statuses = setOf(PostStatus.DRAFT, PostStatus.HIDDEN))).totalElements)
        assertEquals(2, db.posts.page(query(board, statuses = PostStatus.entries.toSet(), author = "y")).totalElements)
        assertEquals("01234", db.posts.page(query(board, author = "x", excerpt = 5)).values.single().excerpt)
    }

    @Test
    fun `search matches title or body case-insensitively and wildcards are literal`() {
        val board = db.newBoard()
        val pct = db.newPost(board, title = "100% sure").id
        val under = db.newPost(board, title = "a_b").id
        val plain = db.newPost(board, title = "ab").id
        val bang = db.newPost(board, title = "wow", body = "50! off").id
        val upper = db.newPost(board, title = "Kotlin Tips").id

        fun hits(q: String) = db.posts.page(query(board, q = q)).values.map { it.id }.toSet()
        assertEquals(setOf(pct), hits("100%"))
        assertEquals(setOf(pct), hits("%"))
        assertEquals(setOf(under), hits("a_b"))
        assertEquals(setOf(under), hits("_"))
        assertEquals(setOf(bang), hits("50!"))
        assertEquals(setOf(upper), hits("kotlin"))
        assertEquals(setOf(upper), hits("TIPS"))
        assertEquals(setOf(bang), hits("OFF"))
        assertEquals(emptySet(), hits("zzz"))
        assertTrue(plain !in hits("a_b"))
    }

    @Test
    fun `sorting by reactions comments and by one reaction type`() {
        val board = db.newBoard()
        val liked = db.newPost(board, title = "liked", at = T0)
        val disliked = db.newPost(board, title = "disliked", at = T0.plusSeconds(1))
        val discussed = db.newPost(board, title = "discussed", at = T0.plusSeconds(2))
        listOf("u1", "u2").forEach { db.reactions.react(ReactionTarget.POST, liked.id, it, "LIKE", ReactionMode.SINGLE, T0) }
        db.reactions.react(ReactionTarget.POST, disliked.id, "u1", "DISLIKE", ReactionMode.SINGLE, T0)
        db.comments.insert(dev.sumin.skeleton.board.NewComment(discussed.id, null, null, 0, "u1", "c", T0))
        db.comments.insert(dev.sumin.skeleton.board.NewComment(discussed.id, null, null, 0, "u2", "c", T0))

        assertEquals(listOf(liked.id, disliked.id, discussed.id), db.posts.page(query(board, PostSort.REACTIONS)).values.map { it.id })
        assertEquals(listOf(discussed.id), db.posts.page(query(board, PostSort.COMMENTS)).values.map { it.id }.take(1))
        assertEquals(disliked.id, db.posts.page(query(board, PostSort.REACTIONS, reaction = "DISLIKE")).values.first().id)
        assertEquals(liked.id, db.posts.page(query(board, PostSort.REACTIONS, reaction = "LIKE")).values.first().id)
    }
}
