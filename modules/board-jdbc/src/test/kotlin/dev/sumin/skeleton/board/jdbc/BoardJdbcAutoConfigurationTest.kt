package dev.sumin.skeleton.board.jdbc

import dev.sumin.skeleton.board.BoardAutoConfiguration
import dev.sumin.skeleton.board.BoardRepository
import dev.sumin.skeleton.board.BoardService
import dev.sumin.skeleton.board.CommentRepository
import dev.sumin.skeleton.board.PostRepository
import dev.sumin.skeleton.board.ReactionRepository
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType
import org.springframework.transaction.PlatformTransactionManager

class BoardJdbcAutoConfigurationTest {
    private val dataSource: DataSource = EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).build()

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(BoardJdbcAutoConfiguration::class.java, BoardAutoConfiguration::class.java))
        .withBean(DataSource::class.java, { dataSource })
        .withBean(PlatformTransactionManager::class.java, { DataSourceTransactionManager(dataSource) })
        .withBean(SqlDialect::class.java, { FakeDialect })

    @Test
    fun `the four repository ports are JDBC implementations and the board services start on them`() {
        runner.run { ctx ->
            assertIs<JdbcBoardRepository>(ctx.getBean(BoardRepository::class.java))
            assertIs<JdbcPostRepository>(ctx.getBean(PostRepository::class.java))
            assertIs<JdbcCommentRepository>(ctx.getBean(CommentRepository::class.java))
            assertIs<JdbcReactionRepository>(ctx.getBean(ReactionRepository::class.java))
            ctx.getBean(BoardService::class.java)
        }
    }

    @Test
    fun `an app repository replaces the JDBC one`() {
        val own = object : PostRepository by JdbcPostRepository(
            org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(dataSource),
            org.springframework.transaction.support.TransactionTemplate(), FakeDialect,
        ) {}
        runner.withBean(PostRepository::class.java, { own }).run { ctx ->
            assertEquals(1, ctx.getBeansOfType(PostRepository::class.java).size)
            assertEquals(own, ctx.getBean(PostRepository::class.java))
        }
    }
}

private object FakeDialect : SqlDialect {
    override val vendor = "h2"
    override fun instantParam(value: java.time.Instant?): Any? = value
    override fun readInstant(rs: java.sql.ResultSet, column: String): java.time.Instant? = null
    override fun insertIgnore(table: String, columns: List<String>, conflictColumns: List<String>) = ""
}
