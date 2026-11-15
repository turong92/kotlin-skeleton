package dev.sumin.skeleton.routing.jdbc

import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import dev.sumin.skeleton.routing.LocalRouteWindowStore
import javax.sql.DataSource
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * 로컬 창 저장소([LocalRouteWindowStore])를 JDBC(PostgreSQL · MySQL)로 구현한다 — `skeleton.routing.enabled=true` 일 때만(이 모듈을 붙여만 둔 앱은 아무것도 안 선다).
 * `request-routing` 의 자동 구성이 이 클래스 뒤에 평가된다(그쪽 `afterName` — 이 클래스 이름을 바꾸지 않는다). 스키마는 모듈 마이그레이션 `db/migration/<vendor>/V20261116140000__routing_local_windows.sql` 을
 * 앱의 `spring.flyway.locations=classpath:db/migration/{vendor}` 가 고른다. SqlDialect 는 조건에 넣지 않는다(방언 자동설정보다 먼저 평가된다 - 존재는 SqlDialectVerifier 가 보장).
 */
@AutoConfiguration(after = [DataSourceAutoConfiguration::class, JdbcClientAutoConfiguration::class])
@ConditionalOnProperty("skeleton.routing.enabled", havingValue = "true")
@ConditionalOnBean(DataSource::class, JdbcClient::class)
class RequestRoutingJdbcAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(LocalRouteWindowStore::class)
    fun jdbcLocalRouteWindowStore(jdbc: JdbcClient, dialect: SqlDialect): LocalRouteWindowStore = JdbcLocalRouteWindowStore(jdbc, dialect)
}
