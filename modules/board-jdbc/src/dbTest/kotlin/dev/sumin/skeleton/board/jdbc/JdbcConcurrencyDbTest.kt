package dev.sumin.skeleton.board.jdbc

import dev.sumin.skeleton.board.CommentStatus
import dev.sumin.skeleton.board.ReactionMode
import dev.sumin.skeleton.board.ReactionTarget.COMMENT
import dev.sumin.skeleton.board.ReactionTarget.POST
import dev.sumin.skeleton.board.jdbc.BoardDb.T0
import kotlin.test.Test
import kotlin.test.assertEquals

/** 카운터 · 반응 규칙이 동시 요청에서도 정확한가 — 스레드마다 별도 커넥션, 같은 순간에 출발한다. */
class JdbcConcurrencyDbTest {
    private val db = BoardDb
    private val n = 40

    private fun reactionRows(table: String, id: Long): Long =
        db.scalar("select count(*) from board_reactions where target_type = :t and target_id = :id", "t" to table, "id" to id)

    @Test
    fun `parallel reactions from different accounts are all counted`() {
        val p = db.newPost(db.newBoard()).id
        parallel(n) { i -> db.reactions.react(POST, p, "u$i", "LIKE", ReactionMode.SINGLE, T0) }
        assertEquals(mapOf("LIKE" to n.toLong()), db.reactions.counts(POST, listOf(p))[p])
        assertEquals(n.toLong(), db.posts.find(p)!!.reactionCount)

        parallel(n) { i -> db.reactions.react(POST, p, "u$i", "DISLIKE", ReactionMode.SINGLE, T0) }
        assertEquals(mapOf("DISLIKE" to n.toLong()), db.reactions.counts(POST, listOf(p))[p])
        assertEquals(n.toLong(), db.posts.find(p)!!.reactionCount)
    }

    @Test
    fun `parallel reactions in PER_TYPE mode keep one row per account and type`() {
        val p = db.newPost(db.newBoard()).id
        parallel(n) { i -> db.reactions.react(POST, p, "u${i % 10}", listOf("LIKE", "EMPATHY")[i % 2], ReactionMode.PER_TYPE, T0) }
        // 계정 u0..u9 는 i % 2 가 계정마다 고정이라 각자 한 종류만 누른다 → 정확히 10 행
        assertEquals(10L, reactionRows("POST", p))
        assertEquals(10L, db.posts.find(p)!!.reactionCount)
        assertEquals(10L, db.reactions.counts(POST, listOf(p))[p]!!.values.sum())
    }

    @Test
    fun `one account racing with itself ends with exactly one reaction in SINGLE mode and a matching counter`() {
        val p = db.newPost(db.newBoard()).id
        parallel(n) { i -> db.reactions.react(POST, p, "same", listOf("LIKE", "DISLIKE", "EMPATHY")[i % 3], ReactionMode.SINGLE, T0) }
        assertEquals(1L, reactionRows("POST", p))
        assertEquals(1L, db.posts.find(p)!!.reactionCount)
    }

    @Test
    fun `racing react and remove by the same accounts never drifts the counter and does not deadlock`() {
        val p = db.newPost(db.newBoard()).id
        parallel(n) { i ->
            repeat(5) {
                if (i % 2 == 0) db.reactions.react(POST, p, "u${i % 7}", "LIKE", ReactionMode.SINGLE, T0)
                else db.reactions.remove(POST, p, "u${i % 7}", null)
            }
        }
        assertEquals(reactionRows("POST", p), db.posts.find(p)!!.reactionCount)
    }

    @Test
    fun `parallel top-level comments are all counted and stored`() {
        val p = db.newPost(db.newBoard()).id
        parallel(n) { i -> db.comment(db.comments, p, author = "u$i") }
        assertEquals(n.toLong(), db.posts.find(p)!!.commentCount)
        assertEquals(n.toLong(), db.scalar("select count(*) from board_comments where post_id = :p", "p" to p))
    }

    @Test
    fun `parallel replies, status changes and reactions on the same thread neither deadlock nor drift the counters`() {
        val p = db.newPost(db.newBoard()).id
        val root = db.comment(db.comments, p)
        parallel(n) { i ->
            when (i % 4) {
                0 -> db.comment(db.comments, p, root.id, root.id, 1, "u$i")
                1 -> db.comments.setStatus(root.id, if (i % 8 == 1) CommentStatus.HIDDEN else CommentStatus.PUBLISHED, T0)
                2 -> db.reactions.react(COMMENT, root.id, "u$i", "LIKE", ReactionMode.SINGLE, T0)
                else -> db.comment(db.comments, p, author = "t$i")
            }
        }
        val published = db.scalar("select count(*) from board_comments where post_id = :p and status = 'PUBLISHED'", "p" to p)
        assertEquals(published, db.posts.find(p)!!.commentCount)
        assertEquals(reactionRows("COMMENT", root.id), db.comments.find(root.id)!!.reactionCount)
    }
}
