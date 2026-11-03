package dev.sumin.skeleton.waitingroom.web

import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.waitingroom.WaitingRoomAutoConfiguration
import dev.sumin.skeleton.waitingroom.WaitingRooms
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean

/**
 * 대기실 HTTP — 서블릿 웹 앱이고 Spring Security(호출자)가 클래스패스에 있고 서비스([WaitingRooms] 빈)가 있을 때만.
 * 사용자 경로 `/api/v1/waiting-rooms` 는 `skeleton.waiting-room.http.enabled=false` 로 끄고, 운영자 경로는 `skeleton.waiting-room.admin.enabled=true` 일 때만 켜진다(꺼져 있으면 404).
 * 두 경로 모두 로그인한 사람만이라 공개 경로는 없다.
 */
@AutoConfiguration(after = [WaitingRoomAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["org.springframework.security.core.Authentication"])
@ConditionalOnBean(WaitingRooms::class)
class WaitingRoomWebAutoConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "skeleton.waiting-room.http", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    fun waitingRoomController(rooms: WaitingRooms): WaitingRoomController = WaitingRoomController(rooms)

    @Bean
    @ConditionalOnProperty(prefix = "skeleton.waiting-room.admin", name = ["enabled"], havingValue = "true")
    @ConditionalOnMissingBean
    fun waitingRoomAdminController(rooms: WaitingRooms, access: OperatorAccess, audit: OperatorAuditLog): WaitingRoomAdminController =
        WaitingRoomAdminController(rooms, access, audit)
}
