package dev.sumin.skeleton.notification.jdbc

import dev.sumin.skeleton.notification.advertising.AdvertisingNoticeStore
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import javax.sql.DataSource
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * 광고 동의 재확인 안내의 표시 저장소(푸시 없이도 쓴다). 푸시를 쓰는 앱은 `notification-push-jdbc` 가 이미 같은 계약의 저장소(`push_advertising_notices`)를 내므로 그쪽이 먼저 서도록
 * 그 자동설정 뒤에 놓는다 - 그러면 이미 안내한 사람이 표 하나가 더 생겼다고 다시 안내받지 않는다. 푸시 쪽 자동설정이 `NotificationAutoConfiguration` 뒤라서 [NotificationJdbcAutoConfiguration] 에 같이 둘 수 없다(순환).
 */
@AutoConfiguration(
    after = [NotificationJdbcAutoConfiguration::class],
    afterName = ["dev.sumin.skeleton.notification.push.jdbc.PushJdbcAutoConfiguration"],
)
@ConditionalOnBean(DataSource::class)
class NotificationJdbcAdvertisingAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(AdvertisingNoticeStore::class)
    fun jdbcAdvertisingNoticeStore(dataSource: DataSource, dialect: SqlDialect): AdvertisingNoticeStore =
        JdbcAdvertisingNoticeStore(NamedParameterJdbcTemplate(dataSource), dialect)
}
