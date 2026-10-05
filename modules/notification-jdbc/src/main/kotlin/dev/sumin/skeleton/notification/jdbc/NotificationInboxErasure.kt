package dev.sumin.skeleton.notification.jdbc

import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.erasure.ErasureRequest
import javax.sql.DataSource
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * 계정이 지워질 때 그 계정의 받은편지함 줄을 지우고, 같은 이벤트를 받은 **다른 사람의** 줄 안 수신자 목록(`recipient_ids_json`)에서 이 계정 id 를 톰스톤으로 바꾼다.
 * 남는 것(한계): 다른 사람의 알림 본문 · payload 에 이 사람이 쓴 글의 내용이 있을 수 있다 — 그 내용은 알림을 만든 모듈의 몫이다.
 * 멱등 — 다시 불러도 같다.
 */
class NotificationInboxErasureListener(private val jdbc: NamedParameterJdbcTemplate) : AccountErasureListener {
    override val name: String = "notification-jdbc"

    override fun erase(request: ErasureRequest) {
        jdbc.update("delete from skeleton_notification_inbox where recipient_id = :a", mapOf("a" to request.accountId))
        jdbc.update(
            "update skeleton_notification_inbox set recipient_ids_json = replace(recipient_ids_json, :a, :t) where recipient_ids_json like :pattern",
            mapOf("a" to request.accountId, "t" to request.tombstone, "pattern" to "%" + request.accountId.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"),
        )
    }
}

@AutoConfiguration(after = [NotificationJdbcAutoConfiguration::class])
@ConditionalOnBean(DataSource::class)
class NotificationJdbcErasureAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = ["notificationInboxErasureListener"])
    fun notificationInboxErasureListener(dataSource: DataSource): AccountErasureListener = NotificationInboxErasureListener(NamedParameterJdbcTemplate(dataSource))
}
