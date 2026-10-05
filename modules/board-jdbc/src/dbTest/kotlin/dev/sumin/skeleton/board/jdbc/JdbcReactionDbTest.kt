package dev.sumin.skeleton.board.jdbc

import dev.sumin.skeleton.board.ReactionMode
import dev.sumin.skeleton.board.ReactionTarget.COMMENT
import dev.sumin.skeleton.board.ReactionTarget.POST
import dev.sumin.skeleton.board.jdbc.BoardDb.T0
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JdbcReactionDbTest {
    private val db = BoardDb
    private val reactions = db.reactions
    private fun post() = db.newPost(db.newBoard()).id

    private fun react(id: Long, who: String, type: String, mode: ReactionMode = ReactionMode.SINGLE) = reactions.react(POST, id, who, type, mode, T0)

    private fun rows(id: Long, who: String? = null) =
        db.scalar("select count(*) from skeleton_board_reactions where target_type = 'POST' and target_id = :id" + (if (who != null) " and account_id = :who" else ""),
            *listOfNotNull("id" to (id as Any), who?.let { "who" to (it as Any) }).toTypedArray())

    @Test
    fun `in SINGLE mode reacting with another type switches, the same type twice changes nothing`() {
        val p = post()
        assertTrue(react(p, "u1", "LIKE"))
        assertTrue(react(p, "u1", "LIKE"))
        assertEquals(mapOf("LIKE" to 1L), reactions.counts(POST, listOf(p))[p])
        assertEquals(1L, db.posts.find(p)!!.reactionCount)

        react(p, "u1", "DISLIKE")
        assertEquals(mapOf("DISLIKE" to 1L), reactions.counts(POST, listOf(p))[p])
        assertEquals(setOf("DISLIKE"), reactions.mine(POST, listOf(p), "u1")[p])
        assertEquals(1L, db.posts.find(p)!!.reactionCount)
    }

    @Test
    fun `in PER_TYPE mode one account keeps one reaction per type`() {
        val p = post()
        react(p, "u1", "LIKE", ReactionMode.PER_TYPE)
        react(p, "u1", "EMPATHY", ReactionMode.PER_TYPE)
        react(p, "u1", "EMPATHY", ReactionMode.PER_TYPE)
        react(p, "u2", "EMPATHY", ReactionMode.PER_TYPE)
        assertEquals(mapOf("LIKE" to 1L, "EMPATHY" to 2L), reactions.counts(POST, listOf(p))[p])
        assertEquals(setOf("LIKE", "EMPATHY"), reactions.mine(POST, listOf(p), "u1")[p])
        assertEquals(3L, db.posts.find(p)!!.reactionCount)
    }

    @Test
    fun `the same table serves both modes - rows made in PER_TYPE collapse when SINGLE reacts`() {
        val p = post()
        react(p, "u1", "LIKE", ReactionMode.PER_TYPE)
        react(p, "u1", "EMPATHY", ReactionMode.PER_TYPE)
        react(p, "u1", "DISLIKE", ReactionMode.SINGLE)
        assertEquals(setOf("DISLIKE"), reactions.mine(POST, listOf(p), "u1")[p])
        assertEquals(1L, db.posts.find(p)!!.reactionCount)
    }

    @Test
    fun `remove drops one type or everything, and removing nothing is harmless`() {
        val p = post()
        react(p, "u1", "LIKE", ReactionMode.PER_TYPE)
        react(p, "u1", "SAD", ReactionMode.PER_TYPE)
        assertTrue(reactions.remove(POST, p, "u1", "LIKE"))
        assertEquals(setOf("SAD"), reactions.mine(POST, listOf(p), "u1")[p])
        assertTrue(reactions.remove(POST, p, "u1", "LIKE"))
        assertTrue(reactions.remove(POST, p, "u1", null))
        assertEquals(0L, rows(p))
        assertEquals(0L, db.posts.find(p)!!.reactionCount)
    }

    @Test
    fun `a missing target is reported and writes nothing`() {
        assertFalse(react(-1, "u1", "LIKE"))
        assertFalse(reactions.remove(POST, -1, "u1", null))
        assertFalse(reactions.react(COMMENT, -1, "u1", "LIKE", ReactionMode.SINGLE, T0))
    }

    @Test
    fun `comment reactions use the same rules and count on the comment`() {
        val c = db.comment(db.comments, post())
        reactions.react(COMMENT, c.id, "u1", "LIKE", ReactionMode.SINGLE, T0)
        reactions.react(COMMENT, c.id, "u2", "LIKE", ReactionMode.SINGLE, T0)
        reactions.react(COMMENT, c.id, "u1", "DISLIKE", ReactionMode.SINGLE, T0)
        assertEquals(mapOf("LIKE" to 1L, "DISLIKE" to 1L), reactions.counts(COMMENT, listOf(c.id))[c.id])
        assertEquals(2L, db.comments.find(c.id)!!.reactionCount)
    }

    @Test
    fun `the unique key is real - a duplicate row cannot be inserted around the repository`() {
        val p = post()
        react(p, "u1", "LIKE")
        val duplicate = runCatching {
            db.jdbc.update(
                "insert into skeleton_board_reactions (target_type, target_id, account_id, reaction_type, created_at) values ('POST', :id, 'u1', 'LIKE', :at)",
                mapOf("id" to p, "at" to DbTestDatabase.dialect.instantParam(T0)),
            )
        }
        assertTrue(duplicate.isFailure)
    }

    @Test
    fun `counts and mine only mention targets that have reactions, for several targets at once`() {
        val p1 = post(); val p2 = post(); val p3 = post()
        react(p1, "u1", "LIKE"); react(p2, "u1", "DISLIKE"); react(p2, "u2", "DISLIKE")
        val counts = reactions.counts(POST, listOf(p1, p2, p3))
        assertEquals(mapOf(p1 to mapOf("LIKE" to 1L), p2 to mapOf("DISLIKE" to 2L)), counts)
        assertEquals(mapOf(p1 to setOf("LIKE"), p2 to setOf("DISLIKE")), reactions.mine(POST, listOf(p1, p2, p3), "u1").filterValues { it.isNotEmpty() }.let { it })
        assertEquals(emptyMap(), reactions.mine(POST, listOf(p1, p2, p3), "nobody"))
        assertEquals(emptyMap(), reactions.counts(POST, emptyList()))
    }
}
