package dev.sumin.skeleton.appcontrol.jdbc

import dev.sumin.skeleton.appcontrol.AppControlStore
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import javax.sql.DataSource
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * 저장([AppControlStore])을 JDBC(PostgreSQL · MySQL)로 구현한다. **`skeleton.app-control.enabled=true` 일 때만 선다** - 꺼진 앱은 이 모듈이 없는 앱과 같다.
 * 스키마는 모듈 마이그레이션 `db/migration/<vendor>/V20261116120000__app_control.sql` 을 앱의 `spring.flyway.locations=classpath:db/migration/{vendor}` 가 고른다.
 * **이 클래스의 이름(`dev.sumin.skeleton.appcontrol.jdbc.AppControlJdbcAutoConfiguration`)을 `app-control` 의 `AppControlAutoConfiguration` 이 문자열로 가리킨다.**
 */
@AutoConfiguration(after = [DataSourceAutoConfiguration::class, DataSourceTransactionManagerAutoConfiguration::class])
@ConditionalOnClass(NamedParameterJdbcTemplate::class)
@ConditionalOnBean(DataSource::class)
@ConditionalOnProperty(prefix = "skeleton.app-control", name = ["enabled"], havingValue = "true")
class AppControlJdbcAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(AppControlStore::class)
    fun jdbcAppControlStore(dataSource: DataSource, transactionManager: PlatformTransactionManager, dialect: SqlDialect): AppControlStore =
        JdbcAppControlStore(NamedParameterJdbcTemplate(dataSource), TransactionTemplate(transactionManager), dialect)
}
