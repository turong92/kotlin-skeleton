package dev.sumin.skeleton.waitingroom.jdbc

import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import dev.sumin.skeleton.waitingroom.WaitingRoomAutoConfiguration
import dev.sumin.skeleton.waitingroom.WaitingRoomStore
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
 * 대기열 저장 포트([WaitingRoomStore])를 JDBC(PostgreSQL · MySQL)로 채운다. 스키마는 모듈 마이그레이션 `db/migration/<vendor>/V20261103110000__waiting_room.sql`(표 셋)을
 * 앱의 `spring.flyway.locations=classpath:db/migration/{vendor}` 가 고른다. 앱이 같은 포트의 빈을 두면 이쪽이 물러난다.
 *
 * **이 클래스의 이름(`dev.sumin.skeleton.waitingroom.jdbc.WaitingRoomJdbcAutoConfiguration`)을 `waiting-room` 의 `WaitingRoomAutoConfiguration` 이 문자열로 가리킨다.**
 */
@AutoConfiguration(
    after = [DataSourceAutoConfiguration::class, DataSourceTransactionManagerAutoConfiguration::class],
    before = [WaitingRoomAutoConfiguration::class],
)
@ConditionalOnClass(NamedParameterJdbcTemplate::class)
@ConditionalOnBean(DataSource::class)
class WaitingRoomJdbcAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(WaitingRoomStore::class)
    fun jdbcWaitingRoomStore(dataSource: DataSource, transactionManager: PlatformTransactionManager, dialect: SqlDialect): WaitingRoomStore =
        JdbcWaitingRoomStore(NamedParameterJdbcTemplate(dataSource), TransactionTemplate(transactionManager), dialect)
}
