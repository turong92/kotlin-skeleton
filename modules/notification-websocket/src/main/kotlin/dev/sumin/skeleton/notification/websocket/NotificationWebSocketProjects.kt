package dev.sumin.skeleton.notification.websocket

import dev.sumin.skeleton.common.project.ProjectContext
import java.security.Principal
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.messaging.support.MessageBuilder
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.server.HandshakeInterceptor

/**
 * 프로젝트 모드의 웹소켓. 세션의 프로젝트는 **연결(핸드셰이크) 요청의 Host** 로 정해지고(서버가 세션 속성에 적는다 — 클라이언트가 바꿀 수 없다) 이후 STOMP 프레임은 그 값을 쓴다.
 * - 사용자 목적지: 세션의 사용자 이름이 `<프로젝트>|<계정 id>` 라서(`ProjectUserPrincipal`) 두 프로젝트에 같은 계정 id 가 있어도 알림이 섞이지 않는다. 브리지가 같은 키로 보낸다.
 * - 전체 공개 토픽: 브로커의 구독 선택자(selector)로 `project` 머리글이 같은 알림만 받는다. 선택자는 서버가 붙이고 클라이언트가 보낸 것은 덮어쓴다.
 */
internal const val PROJECT_SESSION_ATTRIBUTE = "skeleton.project"
internal const val PROJECT_MESSAGE_HEADER = "project"
internal const val SELECTOR_HEADER = "selector"

/** 핸드셰이크 요청의 프로젝트를 세션 속성에 적는다. 프로젝트 없이 온 연결(모르는 Host 는 필터가 이미 421)은 받지 않는다 */
class ProjectHandshakeInterceptor : HandshakeInterceptor {
    override fun beforeHandshake(request: ServerHttpRequest, response: ServerHttpResponse, wsHandler: WebSocketHandler, attributes: MutableMap<String, Any>): Boolean {
        val project = ProjectContext.currentOrNull() ?: return false
        attributes[PROJECT_SESSION_ATTRIBUTE] = project.id
        return true
    }

    override fun afterHandshake(request: ServerHttpRequest, response: ServerHttpResponse, wsHandler: WebSocketHandler, exception: Exception?) = Unit
}

/** 프로젝트 모드의 STOMP 사용자 — 이름이 `<프로젝트>|<계정 id>` ([ProjectContext.scopedKey]). 앱의 원래 principal 은 [principal] */
class ProjectUserPrincipal(val project: String, val principal: Principal) : Principal {
    override fun getName(): String = "$project|${principal.name}"
}

/** 공개 알림 토픽 구독에 이 세션의 프로젝트만 받는 선택자를 붙인다 */
class NotificationWebSocketProjectSelectorInterceptor(
    private val properties: NotificationWebSocketProperties,
) : ChannelInterceptor {
    override fun preSend(message: Message<*>, channel: MessageChannel): Message<*>? {
        val accessor = StompHeaderAccessor.wrap(message)
        if (accessor.command != StompCommand.SUBSCRIBE) return message
        val destination = accessor.destination ?: return message
        val prefix = "/" + properties.broker.notificationDestinationPrefix.trim().trim('/')
        if (destination != prefix && !destination.startsWith("$prefix/")) return message
        val project = accessor.sessionAttributes?.get(PROJECT_SESSION_ATTRIBUTE) as? String
            ?: throw NotificationWebSocketAuthorizationException("WebSocket SUBSCRIBE without a project")
        accessor.setNativeHeader(SELECTOR_HEADER, "headers.$PROJECT_MESSAGE_HEADER == '$project'")
        return MessageBuilder.createMessage(message.payload, accessor.messageHeaders)
    }
}
