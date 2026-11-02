package dev.sumin.skeleton.notification.push.jdbc

import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.erasure.ErasureRequest
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/** 계정이 지워질 때 그 계정의 기기 줄 · 「푸시만 끄기」 줄 · 재확인 안내 표시 · 클릭 기록을 모두 지운다. 멱등 */
class PushErasureListener(private val jdbc: NamedParameterJdbcTemplate) : AccountErasureListener {
    override val name: String = "notification-push-jdbc"

    override fun erase(request: ErasureRequest) {
        jdbc.update("delete from push_devices where account_id = :a", mapOf("a" to request.accountId))
        jdbc.update("delete from push_type_mutes where account_id = :a", mapOf("a" to request.accountId))
        jdbc.update("delete from push_opens where recipient_id = :a", mapOf("a" to request.accountId))
        jdbc.update("delete from push_advertising_notices where account_id = :a", mapOf("a" to request.accountId))
    }
}
