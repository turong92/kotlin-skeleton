package dev.sumin.skeleton.board.jdbc

import dev.sumin.skeleton.board.BoardAccess
import dev.sumin.skeleton.board.BoardActor
import dev.sumin.skeleton.board.BoardAuthorRules
import dev.sumin.skeleton.board.BoardCaller
import dev.sumin.skeleton.board.BoardContentRules
import dev.sumin.skeleton.board.BoardEvent
import dev.sumin.skeleton.board.BoardEventListener
import dev.sumin.skeleton.board.BoardEvents
import dev.sumin.skeleton.board.BoardProperties
import dev.sumin.skeleton.board.AuthorNames
import dev.sumin.skeleton.board.CreatePost
import dev.sumin.skeleton.board.DefaultBoardPolicy
import dev.sumin.skeleton.board.NoopBoardRateLimiter
import dev.sumin.skeleton.board.PostCreated
import dev.sumin.skeleton.board.PostQuery
import dev.sumin.skeleton.board.PostService
import dev.sumin.skeleton.board.PostSort
import dev.sumin.skeleton.board.ReactionSupport
import dev.sumin.skeleton.common.author.AuthorDirectory
import dev.sumin.skeleton.common.time.TimeProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/** 앱의 바깥 트랜잭션에 board 저장소가 실제로 합류했을 때: 롤백이면 행도 이벤트도 없고, 커밋이면 커밋 뒤에 한 번 */
class JdbcEventsDbTest {
    private val db = BoardDb
    private val seen = mutableListOf<BoardEvent>()
    private val props = BoardProperties()
    private val service = PostService(
        BoardAccess(db.boards, db.posts, db.comments, DefaultBoardPolicy()), db.posts, ReactionSupport(db.reactions, props),
        AuthorNames { AuthorDirectory.NONE }, DefaultBoardPolicy(), BoardContentRules(props), props, NoopBoardRateLimiter,
        TimeProvider.fixed(BoardDb.T0), BoardAuthorRules { null }, BoardEvents { listOf(BoardEventListener { seen += it }) },
    )
    private val outer = TransactionTemplate(DataSourceTransactionManager(db.dataSource))
    private fun count(board: String) = db.posts.page(PostQuery(board, 0, 50, PostSort.LATEST)).totalElements

    @Test
    fun `a rollback of the app's transaction leaves neither the row nor an event, a commit sends the event after it`() {
        val board = db.newBoard()
        val caller = BoardCaller("acc_events")
        outer.execute { status ->
            service.create(caller, board, CreatePost("롤백", "본문", null, null))
            assertTrue(seen.isEmpty(), "nothing goes out before the commit")
            status.setRollbackOnly()
        }
        assertEquals(0L, count(board))
        assertTrue(seen.isEmpty(), "a rolled back write sends no event")

        outer.execute { service.create(caller, board, CreatePost("커밋", "본문", null, null)) }
        assertEquals(1L, count(board))
        assertEquals(1, seen.size)
        assertTrue(seen.single() is PostCreated)
    }
}
