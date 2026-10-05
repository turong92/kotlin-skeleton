package dev.sumin.skeleton.board

import dev.sumin.skeleton.common.time.TimeProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BoardServiceTest {
    private val store = FakeStore()
    private fun service(props: BoardProperties = BoardProperties()) =
        BoardService(FakeBoardRepository(store), DefaultBoardPolicy(), props, TimeProvider.fixed(T0))

    private fun failure(block: () -> Unit): BoardErrorCode = assertFailsWith<BoardException> { block() }.errorCode as BoardErrorCode

    @Test
    fun `config reports the configured reaction types, mode, limits and whether the caller moderates`() {
        val props = BoardProperties(reaction = BoardProperties.Reaction(listOf("LIKE", "EMPATHY"), ReactionMode.PER_TYPE), maxCommentDepth = 3)
        val config = service(props).config(moderator)
        assertEquals(listOf("LIKE", "EMPATHY"), config.reactionTypes)
        assertEquals(ReactionMode.PER_TYPE, config.reactionMode)
        assertEquals(3, config.maxCommentDepth)
        assertTrue(config.canModerate)
        assertEquals(false, service(props).config(owner).canModerate)
        assertEquals(false, service(props).config(null).canModerate)
    }

    @Test
    fun `a moderator creates a board and the code can be used once`() {
        val s = service()
        val board = s.create(moderator, "news", "News", "desc")
        assertEquals("news", board.code)
        assertEquals(BoardErrorCode.CODE_TAKEN, failure { s.create(moderator, "news", "Again", null) })
        assertEquals(listOf("news"), s.list().map { it.code })
        assertEquals("News", s.get("news").name)
    }

    @Test
    fun `others cannot create boards and invalid or reserved codes are rejected`() {
        val s = service()
        assertEquals(BoardErrorCode.FORBIDDEN, failure { s.create(owner, "news", "News", null) })
        assertEquals(BoardErrorCode.CONTENT_INVALID, failure { s.create(moderator, "Bad Code", "x", null) })
        assertEquals(BoardErrorCode.CONTENT_INVALID, failure { s.create(moderator, "config", "x", null) })
        assertEquals(BoardErrorCode.CONTENT_INVALID, failure { s.create(moderator, "ok-code", "  ", null) })
    }

    @Test
    fun `an unknown board is BOARD NOT_FOUND`() {
        assertEquals(BoardErrorCode.NOT_FOUND, failure { service().get("nope") })
    }

    @Test
    fun `seeding creates configured boards that are missing and leaves existing ones alone`() {
        val props = BoardProperties(seedBoards = listOf(BoardProperties.SeedBoard("general", "General"), BoardProperties.SeedBoard("qna", "Q and A", "ask")))
        val s = service(props)
        assertEquals(2, s.seed())
        assertEquals(0, s.seed())
        assertEquals(listOf("general", "qna"), s.list().map { it.code }.sorted())
    }
}
