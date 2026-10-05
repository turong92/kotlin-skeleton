package dev.sumin.skeleton.board

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource

class BoardPropertiesTest {
    private fun bind(vararg pairs: Pair<String, String>): BoardProperties =
        Binder(MapConfigurationPropertySource(mapOf(*pairs))).bindOrCreate("skeleton.board", BoardProperties::class.java)

    @Test
    fun `defaults are LIKE and DISLIKE in SINGLE mode with depth 2`() {
        val p = bind()
        assertEquals(listOf("LIKE", "DISLIKE"), p.reaction.types)
        assertEquals(ReactionMode.SINGLE, p.reaction.mode)
        assertEquals(2, p.maxCommentDepth)
        assertEquals("MODERATOR", p.moderatorRole)
    }

    @Test
    fun `reaction types are extended by configuration only`() {
        val p = bind("skeleton.board.reaction.types" to "LIKE,EMPATHY,SAD", "skeleton.board.reaction.mode" to "per-type")
        assertEquals(listOf("LIKE", "EMPATHY", "SAD"), p.reaction.types)
        assertEquals(ReactionMode.PER_TYPE, p.reaction.mode)
    }

    @Test
    fun `a reaction type that is not an upper-case code is rejected`() {
        assertFailsWith<Exception> { bind("skeleton.board.reaction.types" to "like") }
    }

    @Test
    fun `duplicate and empty reaction type lists are rejected`() {
        assertFailsWith<Exception> { bind("skeleton.board.reaction.types" to "LIKE,LIKE") }
        assertFailsWith<Exception> { bind("skeleton.board.reaction.types" to "") }
    }

    @Test
    fun `negative depth and non-positive limits are rejected`() {
        assertFailsWith<Exception> { bind("skeleton.board.max-comment-depth" to "-1") }
        assertFailsWith<Exception> { bind("skeleton.board.title-max-length" to "0") }
    }

    @Test
    fun `a title longer than the 255-character column cannot be configured`() {
        assertFailsWith<Exception> { bind("skeleton.board.title-max-length" to "256") }
        assertEquals(255, bind("skeleton.board.title-max-length" to "255").titleMaxLength)
    }

    @Test
    fun `a seed board with an invalid code is rejected`() {
        assertFailsWith<Exception> { bind("skeleton.board.seed-boards[0].code" to "Bad Code", "skeleton.board.seed-boards[0].name" to "x") }
        assertFailsWith<Exception> { bind("skeleton.board.seed-boards[0].code" to "config", "skeleton.board.seed-boards[0].name" to "x") }
    }
}
