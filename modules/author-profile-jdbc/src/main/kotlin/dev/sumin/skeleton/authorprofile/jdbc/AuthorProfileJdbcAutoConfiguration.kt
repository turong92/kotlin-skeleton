package dev.sumin.skeleton.authorprofile.jdbc

import dev.sumin.skeleton.authorprofile.AuthorProfileAutoConfiguration
import dev.sumin.skeleton.authorprofile.AuthorProfileRepository
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
 * 작성자 프로필 저장소 포트([AuthorProfileRepository])를 JDBC(PostgreSQL · MySQL)로 구현한다.
 * 스키마는 모듈 마이그레이션 `db/migration/<vendor>/V20261007170317__author_profiles.sql` 을 앱의 `spring.flyway.locations=classpath:db/migration/{vendor}` 가 고른다
 * (Flyway 를 안 쓰면 그 SQL 을 schema.sql 에 복사 — MySQL 은 `create table if not exists` 한 문장이라 그대로 두 번 돈다).
 * [AuthorProfileAutoConfiguration] 보다 먼저 평가해 앱이 자기 저장소를 두면 이쪽이 물러난다 (그쪽의 `afterName` 이 이 클래스 이름을 가리킨다).
 * SqlDialect 는 조건에 넣지 않는다 (방언 자동설정보다 먼저 평가된다 — 존재는 SqlDialectVerifier 가 보장).
 */
@AutoConfiguration(
    after = [DataSourceAutoConfiguration::class, DataSourceTransactionManagerAutoConfiguration::class],
    before = [AuthorProfileAutoConfiguration::class],
)
@ConditionalOnClass(NamedParameterJdbcTemplate::class)
@ConditionalOnBean(DataSource::class)
class AuthorProfileJdbcAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(AuthorProfileRepository::class)
    fun jdbcAuthorProfileRepository(dataSource: DataSource, transactionManager: PlatformTransactionManager, dialect: SqlDialect): AuthorProfileRepository =
        JdbcAuthorProfileRepository(NamedParameterJdbcTemplate(dataSource), TransactionTemplate(transactionManager), dialect)
}
