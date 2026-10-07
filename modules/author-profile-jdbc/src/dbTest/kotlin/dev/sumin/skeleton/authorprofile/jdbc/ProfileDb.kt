package dev.sumin.skeleton.authorprofile.jdbc

import com.zaxxer.hikari.HikariDataSource
import dev.sumin.skeleton.authorprofile.AuthorProfile
import dev.sumin.skeleton.authorprofile.AuthorProfileIds
import dev.sumin.skeleton.authorprofile.AuthorProfileStatus
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

/** prepareStatement · createStatement 호출 수를 센다 — "쿼리 한 번" 약속을 단언하려고. */
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

    fun count(block: () -> Unit): Int {
        val before = statements.get()
        block()
        return statements.get() - before
    }
}

/** 진짜 DB(PostgreSQL · MySQL 묶음마다 하나)에 모듈 마이그레이션을 깔고 저장소를 조립한다 — 테스트 클래스들이 공유한다. */
object ProfileDb {
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
    val repository = JdbcAuthorProfileRepository(jdbc, TransactionTemplate(DataSourceTransactionManager(dataSource)), DbTestDatabase.dialect)

    private val sequence = AtomicInteger()

    /** 마이크로초까지 있는 시각 — 두 DB 모두 같은 값으로 돌아오는지 본다 */
    val T0: Instant = Instant.parse("2026-10-08T00:00:00.123456Z")

    /** 테스트마다 새 계정 id 와 이름 접두사 — 서로 간섭하지 않는다 (표를 비우지 않는다) */
    fun account(): String = "acc_t${sequence.incrementAndGet()}_${System.nanoTime() % 1_000_000}"

    fun name(): String = "n${sequence.incrementAndGet()}_${System.nanoTime() % 1_000_000}"

    fun profile(
        account: String,
        primary: Boolean = false,
        name: String? = name(),
        key: String? = name?.lowercase(),
        tag: String? = "0001",
        at: Instant = T0,
        id: String = AuthorProfileIds.newId(),
    ) = AuthorProfile(id, account, if (primary) account else null, name, key, tag, AuthorProfileStatus.ACTIVE, null, at, at, null)

    fun rows(account: String): Long =
        jdbc.queryForObject("select count(*) from author_profiles where account_id = :a", mapOf("a" to account), Long::class.java) ?: 0L
}
