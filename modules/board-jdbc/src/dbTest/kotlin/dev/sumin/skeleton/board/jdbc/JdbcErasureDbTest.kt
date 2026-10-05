package dev.sumin.skeleton.board.jdbc

import dev.sumin.skeleton.board.ReactionMode
import dev.sumin.skeleton.board.ReactionTarget.COMMENT
import dev.sumin.skeleton.board.ReactionTarget.POST
import dev.sumin.skeleton.board.jdbc.BoardDb.T0
import kotlin.test.Test
import kotlin.test.assertEquals

/** 계정이 지워질 때: 작성자가 지워진 사용자로 바뀌고, 글 · 댓글 · 반응 · 카운터는 그대로, 다른 사람 것은 건드리지 않고, 다시 불러도 같다 */
class JdbcErasureDbTest {
    private val db = BoardDb
    private val erasure = JdbcBoardErasureRepository(db.jdbc)
    private val tombstone = "deleted:0123456789abcdef"

    @Test
    fun `posts comments and reactions of the erased account point at the tombstone, counters and others stay`() {
        val board = db.newBoard()
        val mine = db.newPost(board, author = "acc_gone")
        val theirs = db.newPost(board, author = "acc_stay")
        val myComment = db.comment(db.comments, theirs.id, author = "acc_gone")
        val theirComment = db.comment(db.comments, theirs.id, author = "acc_stay")
        db.reactions.react(POST, theirs.id, "acc_gone", "LIKE", ReactionMode.SINGLE, T0)
        db.reactions.react(POST, theirs.id, "acc_stay", "LIKE", ReactionMode.SINGLE, T0)
        db.reactions.react(COMMENT, theirComment.id, "acc_gone", "LIKE", ReactionMode.SINGLE, T0)
        val likesBefore = db.posts.find(theirs.id)!!

        val changed = erasure.anonymizeAuthor("acc_gone", tombstone)

        assertEquals(1 + 1 + 2, changed)
        assertEquals(tombstone, db.posts.find(mine.id)!!.authorId)
        assertEquals("acc_stay", db.posts.find(theirs.id)!!.authorId)
        assertEquals(tombstone, db.comments.find(myComment.id)!!.authorId)
        assertEquals("acc_stay", db.comments.find(theirComment.id)!!.authorId)
        assertEquals(likesBefore.reactionCount, db.posts.find(theirs.id)!!.reactionCount, "the reaction count must not change")
        assertEquals(2L, db.reactions.counts(POST, listOf(theirs.id))[theirs.id]!!["LIKE"])
        assertEquals(emptySet(), db.reactions.mine(POST, listOf(theirs.id), "acc_gone")[theirs.id].orEmpty())
        assertEquals(1L, db.scalar("select count(*) from skeleton_board_reactions where account_id = :a", "a" to tombstone) - db.scalar("select count(*) from skeleton_board_reactions where account_id = :a and target_type = 'COMMENT'", "a" to tombstone))
    }

    @Test
    fun `erasing twice is harmless`() {
        val post = db.newPost(db.newBoard(), author = "acc_twice")
        assertEquals(1, erasure.anonymizeAuthor("acc_twice", tombstone))
        assertEquals(0, erasure.anonymizeAuthor("acc_twice", tombstone))
        assertEquals(tombstone, db.posts.find(post.id)!!.authorId)
    }
}
