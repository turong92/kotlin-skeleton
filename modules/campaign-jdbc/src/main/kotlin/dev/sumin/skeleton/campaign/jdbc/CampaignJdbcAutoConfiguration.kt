package dev.sumin.skeleton.campaign.jdbc

import dev.sumin.skeleton.campaign.CampaignStore
import dev.sumin.skeleton.json.JsonAutoConfiguration
import dev.sumin.skeleton.json.JsonCodec
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

/**
 * 이벤트 저장 포트([CampaignStore])를 JDBC(PostgreSQL · MySQL)로 구현한다. 스키마는 모듈 마이그레이션 `db/migration/<vendor>/V20261028152200__campaigns.sql` 을
 * 앱의 `spring.flyway.locations=classpath:db/migration/{vendor}` 가 고른다. **이 클래스의 이름을 `campaign` 의 `CampaignAutoConfiguration` 이 문자열로 가리킨다**
 * (그쪽은 저장소 빈이 있어야 켜진다). SqlDialect 는 조건에 넣지 않는다 (방언 자동설정보다 먼저 평가된다 - 존재는 SqlDialectVerifier 가 보장).
 */
@AutoConfiguration(after = [DataSourceAutoConfiguration::class, DataSourceTransactionManagerAutoConfiguration::class, JsonAutoConfiguration::class])
@ConditionalOnClass(NamedParameterJdbcTemplate::class)
@ConditionalOnBean(DataSource::class)
class CampaignJdbcAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(CampaignStore::class)
    fun jdbcCampaignStore(dataSource: DataSource, transactionManager: PlatformTransactionManager, dialect: SqlDialect, json: JsonCodec): CampaignStore =
        JdbcCampaignStore(NamedParameterJdbcTemplate(dataSource), transactionManager, dialect, json)
}
