package dev.sumin.skeleton.notification.websocket

import java.security.Principal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.MessageBuilder

class NotificationWebSocketAuthenticationInterceptorTest {
    @Test
    fun `connect is allowed when authentication is disabled`() {
        val interceptor = NotificationWebSocketAuthenticationInterceptor(NotificationWebSocketProperties())
        val message = connectMessage()

        val result = interceptor.preSend(message, NoopMessageChannel)

        assertSame(message, result)
    }

    @Test
    fun `connect is rejected when authentication is enabled without verifier`() {
        val interceptor = NotificationWebSocketAuthenticationInterceptor(
            NotificationWebSocketProperties(
                authentication = NotificationWebSocketProperties.Authentication(enabled = true),
            ),
        )

        assertFailsWith<NotificationWebSocketAuthenticationException> {
            interceptor.preSend(connectMessage("Authorization" to "Bearer access-token"), NoopMessageChannel)
        }
    }

    @Test
    fun `connect is rejected when authentication is enabled without bearer token`() {
        val interceptor = NotificationWebSocketAuthenticationInterceptor(
            properties = NotificationWebSocketProperties(
                authentication = NotificationWebSocketProperties.Authentication(enabled = true),
            ),
            tokenVerifier = NotificationWebSocketTokenVerifier { TestPrincipal("user-1") },
        )

        assertFailsWith<NotificationWebSocketAuthenticationException> {
            interceptor.preSend(connectMessage(), NoopMessageChannel)
        }
    }

    @Test
    fun `connect assigns principal returned by token verifier`() {
        val interceptor = NotificationWebSocketAuthenticationInterceptor(
            properties = NotificationWebSocketProperties(
                authentication = NotificationWebSocketProperties.Authentication(enabled = true),
            ),
            tokenVerifier = NotificationWebSocketTokenVerifier { token ->
                if (token == "access-token") TestPrincipal("user-1") else null
            },
        )

        val result = interceptor.preSend(
            connectMessage("Authorization" to "Bearer access-token"),
            NoopMessageChannel,
        )!!

        val accessor = StompHeaderAccessor.wrap(result)
        assertEquals("user-1", accessor.user?.name)
    }

    @Test
    fun `connect notifies websocket session when principal changes`() {
        val interceptor = NotificationWebSocketAuthenticationInterceptor(
            properties = NotificationWebSocketProperties(
                authentication = NotificationWebSocketProperties.Authentication(enabled = true),
            ),
            tokenVerifier = NotificationWebSocketTokenVerifier { TestPrincipal("user-1") },
        )
        var changedPrincipal: Principal? = null
        val accessor = StompHeaderAccessor.create(StompCommand.CONNECT)
        accessor.addNativeHeader("Authorization", "Bearer access-token")
        accessor.setUserChangeCallback { changedPrincipal = it }
        accessor.setLeaveMutable(true)
        val message = MessageBuilder.createMessage(ByteArray(0), accessor.messageHeaders)

        interceptor.preSend(message, NoopMessageChannel)

        assertEquals("user-1", changedPrincipal?.name)
    }

    private fun connectMessage(vararg nativeHeaders: Pair<String, String>): Message<ByteArray> {
        val accessor = StompHeaderAccessor.create(StompCommand.CONNECT)
        nativeHeaders.forEach { (name, value) -> accessor.addNativeHeader(name, value) }
        return MessageBuilder.createMessage(ByteArray(0), accessor.messageHeaders)
    }

    private data class TestPrincipal(
        private val principalName: String,
    ) : Principal {
        override fun getName(): String = principalName
    }

    private object NoopMessageChannel : MessageChannel {
        override fun send(message: Message<*>): Boolean = true

        override fun send(
            message: Message<*>,
            timeout: Long,
        ): Boolean = true
    }
}
