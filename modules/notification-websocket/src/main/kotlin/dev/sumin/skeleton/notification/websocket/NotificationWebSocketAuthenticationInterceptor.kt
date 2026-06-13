package dev.sumin.skeleton.notification.websocket

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
        val principal = verifier.verify(token)
            ?: throw NotificationWebSocketAuthenticationException("WebSocket CONNECT token was rejected")

        accessor.user = principal
        return if (existingAccessor != null) {
            message
        } else {
            MessageBuilder.createMessage(message.payload, accessor.messageHeaders)
        }
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
