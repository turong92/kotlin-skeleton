package dev.sumin.skeleton.workspace.jdbc

import dev.sumin.skeleton.common.account.AccountFacts
import dev.sumin.skeleton.common.audience.AudienceSource
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import dev.sumin.skeleton.workspace.WorkspaceStore
import javax.sql.DataSource
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager

/**
 * 워크스페이스 저장([WorkspaceStore])을 JDBC(PostgreSQL · MySQL)로 구현한다. 스키마는 모듈 마이그레이션 `db/migration/<vendor>/V20261030100000__workspaces.sql` 을
 * 앱의 `spring.flyway.locations=classpath:db/migration/{vendor}` 가 고른다.
 *
 * **이 클래스의 이름(`dev.sumin.skeleton.workspace.jdbc.WorkspaceJdbcAutoConfiguration`)을 `workspace` 의 `WorkspaceAutoConfiguration` 이 문자열로 가리킨다** - 그쪽은 저장소 빈이 있어야 켜진다.
 * SqlDialect 는 조건에 넣지 않는다 (방언 자동설정보다 먼저 평가된다 - 존재는 SqlDialectVerifier 가 보장).
 */
@AutoConfiguration(after = [DataSourceAutoConfiguration::class, DataSourceTransactionManagerAutoConfiguration::class])
@ConditionalOnClass(NamedParameterJdbcTemplate::class)
@ConditionalOnBean(DataSource::class)
class WorkspaceJdbcAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(WorkspaceStore::class)
    fun jdbcWorkspaceStore(dataSource: DataSource, transactionManager: PlatformTransactionManager, dialect: SqlDialect): WorkspaceStore =
        JdbcWorkspaceStore(NamedParameterJdbcTemplate(dataSource), transactionManager, dialect)

    /** 공지 대상 `workspace`(활성 계정만 거르려고 `AccountFacts` 가 있으면 쓴다) - 공지 모듈이 없으면 아무도 읽지 않는 빈이다 (읽을 때만 DB 를 부른다) */
    @Bean
    @ConditionalOnMissingBean(name = ["workspaceAudience"])
    fun workspaceAudience(dataSource: DataSource, facts: ObjectProvider<AccountFacts>): AudienceSource =
        JdbcWorkspaceAudience(NamedParameterJdbcTemplate(dataSource)) { facts.getIfAvailable() }
}
