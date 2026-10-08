package dev.sumin.skeleton.notification.websocket

import dev.sumin.skeleton.notification.NotificationSubscriber
import dev.sumin.skeleton.notification.NotificationSubscription
import dev.sumin.skeleton.notification.NotificationSubscriptionRegistry
import java.lang.reflect.Type
import java.security.Principal
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration
import org.springframework.context.ApplicationListener
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaders
import org.springframework.messaging.simp.stomp.StompSession
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter
import org.springframework.messaging.converter.StringMessageConverter
import org.springframework.stereotype.Controller
import org.springframework.web.socket.WebSocketHttpHeaders
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.messaging.SessionConnectedEvent
import org.springframework.web.socket.messaging.WebSocketStompClient

/**
 * 실제 서버 + 실제 STOMP 클라이언트로 인바운드 `SEND` 와 구독 인가를 본다.
 * 이 모듈은 서버 → 클라이언트 푸시 전용이라 클라이언트가 브로커 목적지에 `SEND` 해서 남에게 흘릴 수 없어야 한다.
 */
class NotificationWebSocketInboundSecurityIntegrationTest {
    private val contexts = mutableListOf<ConfigurableApplicationContext>()
    private val clients = mutableListOf<WebSocketStompClient>()

    @AfterTest
    fun tearDown() {
        clients.forEach { runCatching { it.stop() } }
        contexts.forEach { runCatching { it.close() } }
    }

    @Test
    fun `authenticated client SEND to a topic destination is not relayed to other subscribers`() {
        val server = startServer(authentication = true)
        val a = server.connect("token-a")
        val b = server.connect("token-b")
        b.subscribe("/topic/notifications/demo")

        a.send("/topic/notifications/demo", "forged-topic")
        a.awaitClosedOrQuiet()
        server.template.convertAndSend("/topic/notifications/demo", "legit-topic")

        b.await("legit-topic")
        assertFalse("forged-topic" in b.received, "SEND 가 구독자에게 중계됐다: ${b.received}")
    }

    @Test
    fun `authenticated client SEND to another user's user destination is not delivered`() {
        val server = startServer(authentication = true)
        val a = server.connect("token-a")
        val b = server.connect("token-b")
        b.subscribe("/user/queue/notifications")

        a.send("/user/user-b/queue/notifications", "forged-user")
        a.awaitClosedOrQuiet()
        server.template.convertAndSendToUser("user-b", "/queue/notifications", "legit-user")

        b.await("legit-user")
        assertFalse("forged-user" in b.received, "사용자 목적지로 SEND 가 전달됐다: ${b.received}")
    }

    @Test
    fun `anonymous client may connect when authentication is disabled but cannot SEND to subscribers`() {
        val server = startServer(authentication = false)
        val anonymous = server.connect(token = null)
        val listener = server.connect(token = null)
        listener.subscribe("/topic/notifications/demo")

        assertTrue(anonymous.session.isConnected, "익명 연결이 핸드셰이크를 통과해야 한다(인증 끔은 문서화된 열린 상태)")
        anonymous.send("/topic/notifications/demo", "forged-anonymous")
        anonymous.awaitClosedOrQuiet()
        server.template.convertAndSend("/topic/notifications/demo", "legit-anonymous")

        listener.await("legit-anonymous")
        assertFalse("forged-anonymous" in listener.received, "익명 SEND 가 중계됐다: ${listener.received}")
    }

    @Test
    fun `client cannot subscribe to another session's raw user queue`() {
        val server = startServer(authentication = true)
        val a = server.connect("token-a")
        val b = server.connect("token-b")
        b.subscribe("/user/queue/notifications")
        val bSessionId = assertNotNull(server.sessionIds["user-b"])

        a.subscribe("/queue/notifications-user$bSessionId")
        a.awaitClosedOrQuiet()
        server.template.convertAndSendToUser("user-b", "/queue/notifications", "private-for-b")

        b.await("private-for-b")
        assertFalse("private-for-b" in a.received, "남의 사용자 큐를 구독해 받았다: ${a.received}")
    }

    @Test
    fun `client cannot subscribe to a broker destination outside the notification topics`() {
        val server = startServer(authentication = true)
        val a = server.connect("token-a")

        a.subscribe("/topic/internal/secret")
        a.awaitClosedOrQuiet()
        server.template.convertAndSend("/topic/internal/secret", "internal")
        val other = server.connect("token-b")
        other.subscribe("/topic/notifications/demo")
        server.template.convertAndSend("/topic/notifications/demo", "marker")
        other.await("marker")

        assertFalse("internal" in a.received, "공개 알림 토픽 밖을 구독해 받았다: ${a.received}")
    }

    @Test
    fun `own user queue and public notification topics stay subscribable`() {
        val server = startServer(authentication = true)
        val a = server.connect("token-a")
        a.subscribe("/topic/notifications/demo")
        a.subscribe("/user/queue/notifications")

        server.template.convertAndSend("/topic/notifications/demo", "topic-ok")
        server.template.convertAndSendToUser("user-a", "/queue/notifications", "user-ok")

        a.await("topic-ok")
        a.await("user-ok")
    }

    @Test
    fun `application destination prefix stays open as the extension point for app handlers`() {
        val server = startServer(authentication = true)
        val a = server.connect("token-a")
        val b = server.connect("token-b")
        b.subscribe("/topic/notifications/pong")

        a.send("/app/ping", "hello")

        b.await("pong")
    }

    private fun startServer(authentication: Boolean): Server {
        val context = SpringApplicationBuilder(TestApplication::class.java)
            .web(WebApplicationType.SERVLET)
            .properties(
                "server.port=0",
                "spring.main.banner-mode=off",
                "skeleton.notification-websocket.authentication.enabled=$authentication",
            )
            .run()
        contexts += context
        val port = (context as WebServerApplicationContext).webServer!!.port
        return Server(
            port = port,
            template = context.getBean(SimpMessagingTemplate::class.java),
            sessionIds = context.getBean(SessionRecorder::class.java).sessionIds,
        )
    }

    private inner class Server(
        val port: Int,
        val template: SimpMessagingTemplate,
        val sessionIds: Map<String, String>,
    ) {
        fun connect(token: String?): Client {
            val stomp = WebSocketStompClient(StandardWebSocketClient()).apply {
                messageConverter = StringMessageConverter()
            }
            clients += stomp
            val client = Client()
            val connectHeaders = StompHeaders().apply {
                if (token != null) set("Authorization", "Bearer $token")
            }
            client.session = stomp.connectAsync(
                "ws://localhost:$port/ws/notifications",
                WebSocketHttpHeaders(),
                connectHeaders,
                client.handler,
            ).get(10, TimeUnit.SECONDS)
            return client
        }
    }

    private class Client {
        val received = CopyOnWriteArrayList<String>()
        val errors = CopyOnWriteArrayList<String>()
        lateinit var session: StompSession
        val handler = object : StompSessionHandlerAdapter() {
            override fun handleFrame(headers: StompHeaders, payload: Any?) {
                errors += "frame:${headers.getFirst("message")}"
            }

            override fun handleTransportError(session: StompSession, exception: Throwable) {
                errors += "transport:${exception.message}"
            }

            override fun getPayloadType(headers: StompHeaders): Type = String::class.java
        }

        fun subscribe(destination: String) {
            val receipt = session.subscribe(destination, object : org.springframework.messaging.simp.stomp.StompFrameHandler {
                override fun getPayloadType(headers: StompHeaders): Type = String::class.java

                override fun handleFrame(headers: StompHeaders, payload: Any?) {
                    received += payload.toString()
                }
            })
            // SUBSCRIBE 는 응답이 없으므로 서버가 처리할 시간을 아주 조금 준다(다음 단계가 전달을 기다리는 쪽이라 정확한 순서는 필요 없다)
            until { receipt.subscriptionId != null }
            Thread.sleep(300)
        }

        fun send(destination: String, payload: String) {
            if (session.isConnected) session.send(destination, payload)
        }

        /** 거절됐다면 서버가 ERROR 로 연결을 닫는다. 아니라면 잠깐 조용히 기다린다. */
        fun awaitClosedOrQuiet() {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1500)
            while (session.isConnected && System.nanoTime() < deadline) Thread.sleep(50)
        }

        fun await(expected: String) {
            until { expected in received }
        }

        private fun until(timeoutMillis: Long = 5000, condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (!condition()) {
                if (System.nanoTime() > deadline) throw TimeoutException("받지 못했다. received=$received errors=$errors")
                Thread.sleep(25)
            }
        }
    }

    class SessionRecorder : ApplicationListener<SessionConnectedEvent> {
        val sessionIds = ConcurrentHashMap<String, String>()

        override fun onApplicationEvent(event: SessionConnectedEvent) {
            val accessor = org.springframework.messaging.simp.stomp.StompHeaderAccessor.wrap(event.message)
            val user = accessor.user?.name ?: return
            accessor.sessionId?.let { sessionIds[user] = it }
        }
    }

    @Controller
    class PingController {
        @MessageMapping("/ping")
        @SendTo("/topic/notifications/pong")
        fun ping(payload: String): String = "pong"
    }

    @Configuration
    @ImportAutoConfiguration(
        TomcatServletWebServerAutoConfiguration::class,
        DispatcherServletAutoConfiguration::class,
        JacksonAutoConfiguration::class,
        NotificationWebSocketAutoConfiguration::class,
    )
    class TestApplication {
        @Bean
        fun registry(): NotificationSubscriptionRegistry = object : NotificationSubscriptionRegistry {
            override fun subscribe(topics: Set<String>, subscriber: NotificationSubscriber): NotificationSubscription =
                object : NotificationSubscription {
                    override val id: String = "noop"
                    override val topics: Set<String> = topics
                    override fun close() = Unit
                }
        }

        @Bean
        fun verifier(): NotificationWebSocketTokenVerifier = NotificationWebSocketTokenVerifier { token ->
            when (token) {
                "token-a" -> NotificationWebSocketPrincipal("user-a")
                "token-b" -> NotificationWebSocketPrincipal("user-b")
                else -> null
            }
        }

        @Bean
        fun sessionRecorder() = SessionRecorder()

        @Bean
        fun pingController() = PingController()
    }
}
