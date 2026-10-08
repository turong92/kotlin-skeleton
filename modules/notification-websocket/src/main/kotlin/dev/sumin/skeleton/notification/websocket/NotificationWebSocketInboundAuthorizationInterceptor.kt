package dev.sumin.skeleton.notification.websocket

import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.ChannelInterceptor

/**
 * 클라이언트 인바운드 채널의 인가. 이 모듈은 서버 → 클라이언트 푸시 전용이다.
 *
 * - `SEND` 는 애플리케이션 목적지 접두사(기본 `/app`) 아래로만 허용한다. 그 아래는 앱이 `@MessageMapping` 으로
 *   직접 받는 자리이고 그 핸들러의 인가는 앱 몫이다. 브로커 목적지(`/topic`, `/queue`, `/user/...`)로 직접 `SEND` 하면
 *   브로커가 그대로 구독자에게 중계하므로 거절한다.
 * - `SUBSCRIBE` 는 공개 알림 토픽, 사용자 목적지 접두사, 그리고 설정으로 더한 접두사만 허용한다.
 *   그 밖의 브로커 목적지(다른 세션의 사용자 큐 포함)는 거절한다.
 *
 * 거절하면 예외가 던져져 STOMP `ERROR` 로 클라이언트에 돌아가고 연결이 닫힌다.
 * 다른 규칙이 필요하면 같은 타입의 빈을 직접 등록해 교체한다.
 */
class NotificationWebSocketInboundAuthorizationInterceptor(
    private val properties: NotificationWebSocketProperties,
) : ChannelInterceptor {
    override fun preSend(
        message: Message<*>,
        channel: MessageChannel,
    ): Message<*>? {
        val accessor = StompHeaderAccessor.wrap(message)
        val destination = accessor.destination
        when (accessor.command) {
            StompCommand.SEND -> if (destination == null || !isUnder(destination, properties.broker.applicationDestinationPrefixes)) {
                throw NotificationWebSocketAuthorizationException("WebSocket SEND is only allowed to application destinations")
            }

            StompCommand.SUBSCRIBE -> if (destination == null || !isUnder(destination, subscribablePrefixes())) {
                throw NotificationWebSocketAuthorizationException("WebSocket SUBSCRIBE to this destination is not allowed")
            }

            else -> Unit
        }
        return message
    }

    private fun subscribablePrefixes(): List<String> {
        val broker = properties.broker
        return listOf(broker.notificationDestinationPrefix, broker.userDestinationPrefix) + broker.extraSubscribeDestinationPrefixes
    }

    private fun isUnder(
        destination: String,
        prefixes: List<String>,
    ): Boolean =
        prefixes.any { raw ->
            val prefix = "/" + raw.trim().trim('/')
            prefix.length > 1 && (destination == prefix || destination.startsWith("$prefix/"))
        }
}

class NotificationWebSocketAuthorizationException(
    message: String,
) : RuntimeException(message)
