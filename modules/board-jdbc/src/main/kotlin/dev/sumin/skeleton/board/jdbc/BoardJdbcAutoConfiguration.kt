package dev.sumin.skeleton.board.jdbc

import dev.sumin.skeleton.board.BoardAutoConfiguration
import dev.sumin.skeleton.board.BoardRepository
import dev.sumin.skeleton.board.CommentRepository
import dev.sumin.skeleton.board.PostRepository
import dev.sumin.skeleton.board.ReactionRepository
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import javax.sql.DataSource
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * 게시판 저장소 포트를 JDBC(PostgreSQL · MySQL)로 구현한다. 스키마는 모듈 마이그레이션 `db/migration/<vendor>/V20261005142218__skeleton_board.sql` 을
 * 앱의 `spring.flyway.locations=classpath:db/migration/{vendor}` 가 고른다 (Flyway 를 안 쓰면 그 SQL 을 schema.sql 에 복사).
 * [BoardAutoConfiguration] 보다 먼저 평가해 앱이 자기 저장소를 두면 이쪽이 물러난다. SqlDialect 는 조건에 넣지 않는다 (방언 자동설정보다 먼저 평가된다 —
 * 존재는 SqlDialectVerifier 가 보장).
 */
@AutoConfiguration(
    after = [DataSourceAutoConfiguration::class, DataSourceTransactionManagerAutoConfiguration::class],
    before = [BoardAutoConfiguration::class],
)
@ConditionalOnClass(NamedParameterJdbcTemplate::class)
@ConditionalOnBean(DataSource::class)
class BoardJdbcAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(BoardRepository::class)
    fun jdbcBoardRepository(dataSource: DataSource, dialect: SqlDialect): BoardRepository =
        JdbcBoardRepository(NamedParameterJdbcTemplate(dataSource), dialect)

    @Bean
    @ConditionalOnMissingBean(PostRepository::class)
    fun jdbcPostRepository(dataSource: DataSource, transactionManager: PlatformTransactionManager, dialect: SqlDialect): PostRepository =
        JdbcPostRepository(NamedParameterJdbcTemplate(dataSource), TransactionTemplate(transactionManager), dialect)

    @Bean
    @ConditionalOnMissingBean(CommentRepository::class)
    fun jdbcCommentRepository(dataSource: DataSource, transactionManager: PlatformTransactionManager, dialect: SqlDialect): CommentRepository =
        JdbcCommentRepository(NamedParameterJdbcTemplate(dataSource), TransactionTemplate(transactionManager), dialect)

    @Bean
    @ConditionalOnMissingBean(ReactionRepository::class)
    fun jdbcReactionRepository(dataSource: DataSource, transactionManager: PlatformTransactionManager, dialect: SqlDialect): ReactionRepository =
        JdbcReactionRepository(NamedParameterJdbcTemplate(dataSource), TransactionTemplate(transactionManager), dialect)
}
