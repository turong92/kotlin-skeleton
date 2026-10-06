package dev.sumin.skeleton.account.jdbc

import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate

/** 진짜 DB(PostgreSQL · MySQL 묶음마다 하나)에 모듈 마이그레이션을 깔고 저장소를 조립한다 — 테스트 클래스들이 공유한다 */
object AccountDb {
    val dataSource: HikariDataSource = DbTestDatabase.dataSource().let { source ->
        source as DriverManagerDataSource
        HikariDataSource().apply { jdbcUrl = source.url; username = source.username; password = source.password; maximumPoolSize = 48 }
    }.also { Flyway.configure().dataSource(it).locations("classpath:db/migration/${DbTestDatabase.vendor}").load().migrate() }

    val jdbc = NamedParameterJdbcTemplate(dataSource)
    private val tx = TransactionTemplate(DataSourceTransactionManager(dataSource))
    val accounts = JdbcAccountRepository(jdbc, tx, DbTestDatabase.dialect)
    val tokens = JdbcOneTimeTokenStore(jdbc, DbTestDatabase.dialect)
    val challenges = JdbcChallengeStore(jdbc, DbTestDatabase.dialect)
    val audit = JdbcAccountAuditListener(jdbc, DbTestDatabase.dialect)

    fun clean() {
        listOf("account_audit", "account_challenges", "account_tokens", "accounts").forEach { jdbc.update("delete from $it", emptyMap<String, Any>()) }
    }
}
