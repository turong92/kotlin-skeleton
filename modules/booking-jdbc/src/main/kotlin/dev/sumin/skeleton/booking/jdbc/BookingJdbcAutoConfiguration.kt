package dev.sumin.skeleton.booking.jdbc

import dev.sumin.skeleton.booking.BookingAutoConfiguration
import dev.sumin.skeleton.booking.BookingRefundQueue
import dev.sumin.skeleton.booking.BookingStore
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
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
import org.springframework.transaction.support.TransactionTemplate

/**
 * 예약 저장 포트([BookingStore])를 JDBC(PostgreSQL · MySQL)로 채운다. 스키마는 모듈 마이그레이션 `db/migration/<vendor>/V20261103100000__booking.sql`(표 여섯)을
 * 앱의 `spring.flyway.locations=classpath:db/migration/{vendor}` 가 고른다. 앱이 같은 포트의 빈을 두면 이쪽이 물러난다.
 *
 * **이 클래스의 이름(`dev.sumin.skeleton.booking.jdbc.BookingJdbcAutoConfiguration`)을 `booking` 의 `BookingAutoConfiguration` 이 문자열로 가리킨다.**
 */
@AutoConfiguration(
    after = [DataSourceAutoConfiguration::class, DataSourceTransactionManagerAutoConfiguration::class],
    before = [BookingAutoConfiguration::class],
)
@ConditionalOnClass(NamedParameterJdbcTemplate::class)
@ConditionalOnBean(DataSource::class)
class BookingJdbcAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(BookingStore::class)
    fun jdbcBookingStore(
        dataSource: DataSource,
        transactionManager: PlatformTransactionManager,
        dialect: SqlDialect,
        refunds: ObjectProvider<BookingRefundQueue>,   // 결제 · 작업 큐가 있을 때만 서는 빈 - 늦은 결제의 환불 작업
    ): BookingStore =
        JdbcBookingStore(NamedParameterJdbcTemplate(dataSource), TransactionTemplate(transactionManager), dialect, refunds = refunds.getIfAvailable())
}
