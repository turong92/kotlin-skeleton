package dev.sumin.skeleton.notification.jdbc

import dev.sumin.skeleton.json.JsonCodec
import dev.sumin.skeleton.json.JsonAutoConfiguration
import dev.sumin.skeleton.notification.NotificationAutoConfiguration
import dev.sumin.skeleton.notification.NotificationInboxRepository
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import javax.sql.DataSource
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

@AutoConfiguration(
    after = [
        DataSourceAutoConfiguration::class,
        JsonAutoConfiguration::class,
    ],
    before = [NotificationAutoConfiguration::class],
)
@ConditionalOnClass(NamedParameterJdbcTemplate::class)
class NotificationJdbcAutoConfiguration {
    @Bean
    @ConditionalOnBean(DataSource::class) // SqlDialect 는 조건에 넣지 않는다: 방언 자동설정보다 먼저 평가된다. 존재는 SqlDialectVerifier 가 보장
    @ConditionalOnMissingBean(NotificationInboxRepository::class)
    fun jdbcNotificationInboxRepository(
        dataSource: DataSource,
        jsonCodec: JsonCodec,
        dialect: SqlDialect,
    ): NotificationInboxRepository =
        JdbcNotificationInboxRepository(
            jdbc = NamedParameterJdbcTemplate(dataSource),
            jsonCodec = jsonCodec,
            dialect = dialect,
        )
}
