package dev.sumin.skeleton.board.jdbc

import com.zaxxer.hikari.HikariDataSource
import dev.sumin.skeleton.board.CommentRepository
import dev.sumin.skeleton.board.NewComment
import dev.sumin.skeleton.board.NewPost
import dev.sumin.skeleton.board.Post
import dev.sumin.skeleton.board.PostStatus
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate

/** prepareStatement · createStatement 호출 수를 센다 — N+1 이 없는지(쿼리 수가 행 수와 무관한지) 단언하려고. */
class CountingDataSource(private val delegate: DataSource) : DataSource by delegate {
    val statements = AtomicInteger()

    override fun getConnection(): Connection = wrap(delegate.connection)

    override fun getConnection(username: String?, password: String?): Connection = wrap(delegate.getConnection(username, password))

    private fun wrap(connection: Connection): Connection =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            if (method.name in setOf("prepareStatement", "createStatement", "prepareCall")) statements.incrementAndGet()
            try {
                method.invoke(connection, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as Connection

    /** [block] 이 보낸 SQL 문장 수 */
    fun count(block: () -> Unit): Int {
        val before = statements.get()
        block()
        return statements.get() - before
    }
}

/** 진짜 DB(PostgreSQL · MySQL 묶음마다 하나)에 모듈 마이그레이션을 깔고 저장소를 조립한다 — 테스트 클래스들이 공유한다. */
object BoardDb {
    private val raw: HikariDataSource = DbTestDatabase.dataSource().let { source ->
        source as DriverManagerDataSource
        HikariDataSource().apply {
            jdbcUrl = source.url
            username = source.username
            password = source.password
            maximumPoolSize = 48
        }
    }.also { Flyway.configure().dataSource(it).locations("classpath:db/migration/${DbTestDatabase.vendor}").load().migrate() }

    val dataSource = CountingDataSource(raw)
    val jdbc = NamedParameterJdbcTemplate(dataSource)
    private val tx = TransactionTemplate(DataSourceTransactionManager(dataSource))
    val boards = JdbcBoardRepository(jdbc, DbTestDatabase.dialect)
    val posts = JdbcPostRepository(jdbc, tx, DbTestDatabase.dialect)
    val comments = JdbcCommentRepository(jdbc, tx, DbTestDatabase.dialect)
    val reactions = JdbcReactionRepository(jdbc, tx, DbTestDatabase.dialect)

    private val sequence = AtomicInteger()

    val T0: Instant = Instant.parse("2026-03-01T00:00:00.123456Z")

    /** 테스트마다 새 게시판 — 서로 간섭하지 않는다 */
    fun newBoard(): String = "t-${sequence.incrementAndGet()}-${System.nanoTime() % 100000}".also { boards.create(it, "Board $it", null, T0) }

    fun newPost(
        board: String,
        author: String = "acc_a",
        title: String = "title",
        body: String = "body",
        status: PostStatus = PostStatus.PUBLISHED,
        attachments: List<String> = emptyList(),
        at: Instant = T0,
    ): Post = posts.insert(NewPost(board, author, title, body, status, attachments, at))

    fun comment(repo: CommentRepository, post: Long, parent: Long? = null, root: Long? = null, depth: Int = 0, author: String = "acc_a", at: Instant = T0) =
        repo.insert(NewComment(post, parent, root, depth, author, "c", at))!!

    fun scalar(sql: String, vararg params: Pair<String, Any>): Long =
        jdbc.queryForObject(sql, params.toMap(), Long::class.java) ?: 0L
}
