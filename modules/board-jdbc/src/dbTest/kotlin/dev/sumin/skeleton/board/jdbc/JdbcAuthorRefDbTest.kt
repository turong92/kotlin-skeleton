package dev.sumin.skeleton.board.jdbc

import dev.sumin.skeleton.board.BoardAuthors
import dev.sumin.skeleton.board.NewComment
import dev.sumin.skeleton.board.NewPost
import dev.sumin.skeleton.board.PostQuery
import dev.sumin.skeleton.board.PostSort
import dev.sumin.skeleton.board.PostStatus
import dev.sumin.skeleton.board.jdbc.BoardDb.T0
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** author_ref(공개 작성자 id) 열: 왕복, 넘겨받기 포트(pendingAccounts · adopt), 탈퇴 뒤에도 그대로 */
class JdbcAuthorRefDbTest {
    private val db = BoardDb
    private val adoption = JdbcBoardAuthorAdoption(db.jdbc)
    private fun account() = "acc_" + UUID.randomUUID().toString().replace("-", "").take(16)

    @Test
    fun `author_ref round-trips as null and as a value for posts, list items and comments`() {
        val board = db.newBoard()
        val legacy = db.newPost(board)
        val own = db.posts.insert(NewPost(board, "acc_x", "t", "b", PostStatus.PUBLISHED, emptyList(), T0, authorRef = "prf_aaa"))
        assertNull(db.posts.find(legacy.id)!!.authorRef)
        assertEquals("prf_aaa", db.posts.find(own.id)!!.authorRef)
        val listed = db.posts.page(PostQuery(board, 0, 10, PostSort.LATEST)).values.associate { it.id to it.authorRef }
        assertTrue(legacy.id in listed && own.id in listed, "both posts must be listed before their author_ref is compared")
        assertNull(listed[legacy.id])
        assertEquals("prf_aaa", listed[own.id])

        val c0 = db.comment(db.comments, own.id)
        val c1 = db.comments.insert(NewComment(own.id, null, null, 0, "acc_x", "c", T0, authorRef = "prf_bbb"))!!
        assertNull(db.comments.find(c0.id)!!.authorRef)
        assertEquals("prf_bbb", db.comments.find(c1.id)!!.authorRef)
        val roots = db.comments.rootPage(dev.sumin.skeleton.board.CommentRootQuery(own.id, 0, 10, dev.sumin.skeleton.board.CommentSort.OLDEST)).values
        assertEquals(setOf<String?>(null, "prf_bbb"), roots.map { it.authorRef }.toSet())
    }

    @Test
    fun `pendingAccounts lists accounts with unadopted posts or comments and skips tombstones and adopted rows`() {
        val board = db.newBoard()
        val postOnly = account()
        val commentOnly = account()
        val adopted = account()
        val gone = BoardAuthors.DELETED_PREFIX + "0123456789abcdef"
        val p = db.newPost(board, author = postOnly)
        val many = account()
        val first = "acc_ord1_" + UUID.randomUUID().toString().replace("-", "")
        val second = "acc_ord2_" + UUID.randomUUID().toString().replace("-", "")
        db.newPost(board, author = many)
        db.newPost(board, author = many)
        db.comment(db.comments, p.id, author = many)
        db.newPost(board, author = second)
        db.newPost(board, author = first)
        db.newPost(board, author = gone)
        db.posts.insert(NewPost(board, adopted, "t", "b", PostStatus.PUBLISHED, emptyList(), T0, authorRef = "prf_x"))
        db.comment(db.comments, p.id, author = commentOnly)
        db.comment(db.comments, p.id, author = gone)

        val pending = adoption.pendingAccounts(100_000)
        assertTrue(postOnly in pending)
        assertTrue(commentOnly in pending)
        assertFalse(adopted in pending)
        assertFalse(pending.any { it.startsWith(BoardAuthors.DELETED_PREFIX) })
        assertEquals(pending.size, pending.toSet().size, "distinct")
        assertEquals(1, pending.count { it == many }, "an account with two posts and a comment is listed once")
        assertEquals(listOf(first, second), pending.filter { it == first || it == second }, "ordered by author_id")
        assertEquals(1, adoption.pendingAccounts(1).size, "limit caps the list")
    }

    @Test
    fun `adopt fills author_ref on posts and comments of that account only, and is idempotent`() {
        val board = db.newBoard()
        val me = account()
        val other = account()
        val post = db.newPost(board, author = me)
        val mineC = db.comment(db.comments, post.id, author = me)
        val otherP = db.newPost(board, author = other)
        val chosen = db.posts.insert(NewPost(board, me, "t", "b", PostStatus.PUBLISHED, emptyList(), T0, authorRef = "prf_chosen"))

        assertEquals(2, adoption.adopt(me, "prf_first"))
        assertEquals("prf_first", db.posts.find(post.id)!!.authorRef)
        assertEquals("prf_first", db.comments.find(mineC.id)!!.authorRef)
        assertEquals("prf_chosen", db.posts.find(chosen.id)!!.authorRef, "an already chosen author_ref is never overwritten")
        assertNull(db.posts.find(otherP.id)!!.authorRef)
        assertEquals(0, adoption.adopt(me, "prf_second"))
        assertEquals("prf_first", db.posts.find(post.id)!!.authorRef)
        assertFalse(me in adoption.pendingAccounts(100_000))
        assertTrue(other in adoption.pendingAccounts(100_000))
    }

    @Test
    fun `erasing the account leaves author_ref untouched`() {
        val board = db.newBoard()
        val me = account()
        val post = db.posts.insert(NewPost(board, me, "t", "b", PostStatus.PUBLISHED, emptyList(), T0, authorRef = "prf_keep"))
        val c = db.comments.insert(NewComment(post.id, null, null, 0, me, "c", T0, authorRef = "prf_keep2"))!!
        JdbcBoardErasureRepository(db.jdbc).anonymizeAuthor(me, "deleted:feedfeedfeedfeed")
        assertEquals("deleted:feedfeedfeedfeed", db.posts.find(post.id)!!.authorId)
        assertEquals("prf_keep", db.posts.find(post.id)!!.authorRef)
        assertEquals("prf_keep2", db.comments.find(c.id)!!.authorRef)
    }
}
