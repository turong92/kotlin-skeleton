package dev.sumin.skeleton.notification.jdbc

import dev.sumin.skeleton.json.JsonCodec
import dev.sumin.skeleton.json.JsonAutoConfiguration
import dev.sumin.skeleton.notification.NotificationAutoConfiguration
import dev.sumin.skeleton.notification.NotificationInboxRepository
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
    @ConditionalOnBean(DataSource::class)
    @ConditionalOnMissingBean(NotificationInboxRepository::class)
    fun jdbcNotificationInboxRepository(
        dataSource: DataSource,
        jsonCodec: JsonCodec,
    ): NotificationInboxRepository =
        JdbcNotificationInboxRepository(
            jdbc = NamedParameterJdbcTemplate(dataSource),
            jsonCodec = jsonCodec,
        )
}
