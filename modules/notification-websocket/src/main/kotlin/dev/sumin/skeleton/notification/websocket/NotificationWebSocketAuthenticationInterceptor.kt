package dev.sumin.skeleton.notification.websocket

import dev.sumin.skeleton.common.project.ProjectContext
import dev.sumin.skeleton.common.project.ProjectRegistry
import java.security.Principal
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.support.MessageHeaderAccessor
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.messaging.support.MessageBuilder

class NotificationWebSocketAuthenticationInterceptor(
    private val properties: NotificationWebSocketProperties,
    private val tokenVerifier: NotificationWebSocketTokenVerifier? = null,
    private val projects: ProjectRegistry? = null,
) : ChannelInterceptor {
    override fun preSend(
        message: Message<*>,
        channel: MessageChannel,
    ): Message<*>? {
        if (!properties.authentication.enabled) {
            return message
        }

        val existingAccessor = MessageHeaderAccessor
            .getAccessor(message, StompHeaderAccessor::class.java)
            ?.takeIf { it.isMutable }
        val accessor = existingAccessor ?: StompHeaderAccessor.wrap(message)
        if (accessor.command != StompCommand.CONNECT) {
            return message
        }

        val verifier = tokenVerifier
            ?: throw NotificationWebSocketAuthenticationException("WebSocket authentication is enabled but no token verifier is configured")
        val token = bearerToken(accessor)
            ?: throw NotificationWebSocketAuthenticationException("WebSocket CONNECT is missing a bearer token")
        val principal = verifyIn(accessor, verifier, token)
            ?: throw NotificationWebSocketAuthenticationException("WebSocket CONNECT token was rejected")

        accessor.user = principal
        return if (existingAccessor != null) {
            message
        } else {
            MessageBuilder.createMessage(message.payload, accessor.messageHeaders)
        }
    }

    /** 프로젝트 모드: 토큰은 연결한 도메인의 프로젝트 문맥에서 검증하고(프로젝트마다 JWT 키가 다르다), 사용자 이름에 프로젝트를 붙인다 */
    private fun verifyIn(accessor: StompHeaderAccessor, verifier: NotificationWebSocketTokenVerifier, token: String): Principal? {
        val registry = projects ?: return verifier.verify(token)
        val id = accessor.sessionAttributes?.get(PROJECT_SESSION_ATTRIBUTE) as? String
            ?: throw NotificationWebSocketAuthenticationException("WebSocket CONNECT has no project (the handshake did not record one)")
        val project = registry.active.firstOrNull { it.id == id }
            ?: throw NotificationWebSocketAuthenticationException("WebSocket CONNECT for project '$id' which is not in service")
        return ProjectContext.runIn(project) { verifier.verify(token) }?.let { ProjectUserPrincipal(project.id, it) }
    }

    internal fun verifyForTest(token: String): Principal? =
        tokenVerifier?.verify(token)

    private fun bearerToken(accessor: StompHeaderAccessor): String? {
        val authentication = properties.authentication
        val authorization = accessor.getFirstNativeHeader(authentication.authorizationHeader)
            ?: accessor.getFirstNativeHeader(authentication.authorizationHeader.lowercase())
        val bearer = authorization
            ?.trim()
            ?.takeIf { it.startsWith(authentication.bearerPrefix, ignoreCase = true) }
            ?.substring(authentication.bearerPrefix.length)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (bearer != null) {
            return bearer
        }

        return accessor.getFirstNativeHeader(authentication.tokenHeader)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }
}

class NotificationWebSocketAuthenticationException(
    message: String,
) : RuntimeException(message)
