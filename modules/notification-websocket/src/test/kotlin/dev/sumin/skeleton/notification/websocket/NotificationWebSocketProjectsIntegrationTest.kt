package dev.sumin.skeleton.notification.websocket

import dev.sumin.skeleton.common.project.ProjectAutoConfiguration
import dev.sumin.skeleton.common.project.ProjectContext
import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationSubscriber
import dev.sumin.skeleton.notification.NotificationSubscription
import dev.sumin.skeleton.notification.NotificationSubscriptionRegistry
import java.lang.reflect.Type
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.messaging.simp.stomp.StompFrameHandler
import org.springframework.messaging.simp.stomp.StompHeaders
import org.springframework.messaging.simp.stomp.StompSession
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter
import org.springframework.web.socket.WebSocketHttpHeaders
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.messaging.WebSocketStompClient

/**
 * 프로젝트 모드의 실제 서버 + 실제 STOMP 클라이언트: 연결한 도메인(Host)이 세션의 프로젝트다.
 * 두 프로젝트에 같은 계정 id(`u1`)가 있어도 사용자 알림이 남의 프로젝트 세션에 가지 않고, 전체 공개 토픽도 프로젝트 안에서만 돈다.
 */
class NotificationWebSocketProjectsIntegrationTest {
    private val contexts = mutableListOf<ConfigurableApplicationContext>()
    private val clients = mutableListOf<WebSocketStompClient>()

    @AfterTest
    fun tearDown() {
        clients.forEach { runCatching { it.stop() } }
        contexts.forEach { runCatching { it.close() } }
    }

    @Test
    fun `a notification for user u1 reaches only the session of the project that published it - the same account id in the other project hears nothing`() {
        val server = start()
        val a = server.connect("a.localhost")
        val b = server.connect("b.localhost")
        listOf(a, b).forEach { it.subscribe("/user/queue/notifications"); it.subscribe("/topic/notifications/news") }

        server.publishIn("a", NotificationEvent(topic = "news", type = "t", payload = mapOf("userId" to "u1", "from" to "a-event")))
        a.await("user:")
        a.await("topic:")
        // a 가 받았다 = 전달이 끝났다. 이제 b 에서 낸 표식이 b 에 닿는 것을 기다리고, 그 사이 b 에 a 의 것이 섞였는지 본다
        server.publishIn("b", NotificationEvent(topic = "news", type = "t", payload = mapOf("from" to "b-event")))
        b.await("topic:")

        assertTrue(a.received.all { "b-event" !in it }, "a 가 b 의 알림을 받았다: ${a.received}")
        assertEquals(2, a.received.size, a.received.toString())
        assertEquals(1, b.received.size, "b 가 a 의 알림을 받았다(사용자 알림 또는 공개 토픽): ${b.received}")
        assertTrue("b-event" in b.received.single(), b.received.toString())
    }

    @Test
    fun `a notification published without a project reaches no project session`() {
        val server = start()
        val a = server.connect("a.localhost")
        a.subscribe("/topic/notifications/news")

        server.publishIn(null, NotificationEvent(topic = "news", type = "t", payload = mapOf("from" to "no-project")))
        server.publishIn("a", NotificationEvent(topic = "news", type = "t", payload = mapOf("from" to "a-event")))

        a.await("topic:")
        assertEquals(1, a.received.size, "프로젝트 없는 알림이 프로젝트 세션에 갔다: ${a.received}")
        assertTrue("a-event" in a.received.single(), a.received.toString())
    }

    @Test
    fun `the token is verified inside the project of the domain the client connected to`() {
        val server = start()
        server.connect("b.localhost")
        assertEquals(listOf("b"), server.verifiedIn)
    }

    private fun start(): Server {
        val context = SpringApplicationBuilder(TestApplication::class.java)
            .web(WebApplicationType.SERVLET)
            .properties(
                "server.port=0", "spring.main.banner-mode=off", "logging.level.org.springframework.messaging=TRACE",
                "skeleton.notification-websocket.authentication.enabled=true",
                "skeleton.projects.a.hosts=a.localhost", "skeleton.projects.a.schema=pa",
                "skeleton.projects.b.hosts=b.localhost", "skeleton.projects.b.schema=pb",
            )
            .run()
        contexts += context
        val app = context.getBean(TestApplication::class.java)
        return Server((context as WebServerApplicationContext).webServer!!.port, context.getBean(ProjectRegistry::class.java), app)
    }

    private inner class Server(val port: Int, val projects: ProjectRegistry, val app: TestApplication) {
        val verifiedIn get() = app.verifiedIn

        fun publishIn(project: String?, event: NotificationEvent) {
            val subscriber = app.subscriber ?: error("the bridge has not subscribed")
            if (project == null) subscriber.onNotification(event)
            else ProjectContext.runIn(projects.projects.first { it.id == project }) { subscriber.onNotification(event) }
        }

        fun connect(host: String): Client {
            val stomp = WebSocketStompClient(StandardWebSocketClient()).apply { messageConverter = org.springframework.messaging.converter.JacksonJsonMessageConverter() }
            clients += stomp
            val client = Client()
            client.session = stomp.connectAsync(
                "ws://$host:$port/ws/notifications", WebSocketHttpHeaders(),
                StompHeaders().apply { set("Authorization", "Bearer any-token") }, client.handler,
            ).get(10, TimeUnit.SECONDS)
            return client
        }
    }

    private class Client {
        val received = CopyOnWriteArrayList<String>()
        lateinit var session: StompSession
        val errors = CopyOnWriteArrayList<String>()
        val handler = object : StompSessionHandlerAdapter() {
            override fun getPayloadType(headers: StompHeaders): Type = Map::class.java
            override fun handleFrame(headers: StompHeaders, payload: Any?) { errors += "frame:${headers.getFirst("message")}" }
            override fun handleTransportError(session: StompSession, exception: Throwable) { errors += "transport:${exception.message}" }
        }

        fun subscribe(destination: String) {
            session.subscribe(destination, object : StompFrameHandler {
                override fun getPayloadType(headers: StompHeaders): Type = Map::class.java
                override fun handleFrame(headers: StompHeaders, payload: Any?) {
                    // 사용자 큐로 온 것과 공개 토픽으로 온 것을 구분해 적는다
                    received += (if (destination.startsWith("/user")) "user:" else "topic:") + payload
                }
            })
            Thread.sleep(300)
        }

        fun await(expected: String, timeoutMillis: Long = 5000) {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (received.none { it.startsWith(expected) }) {
                if (System.nanoTime() > deadline) throw TimeoutException("받지 못했다. received=$received errors=$errors")
                Thread.sleep(25)
            }
        }
    }

    @Configuration
    @ImportAutoConfiguration(
        TomcatServletWebServerAutoConfiguration::class,
        DispatcherServletAutoConfiguration::class,
        JacksonAutoConfiguration::class,
        ProjectAutoConfiguration::class,
        NotificationWebSocketAutoConfiguration::class,
    )
    class TestApplication {
        @Volatile var subscriber: NotificationSubscriber? = null
        val verifiedIn = CopyOnWriteArrayList<String>()

        @Bean
        fun registry(): NotificationSubscriptionRegistry = object : NotificationSubscriptionRegistry {
            override fun subscribe(topics: Set<String>, subscriber: NotificationSubscriber): NotificationSubscription {
                this@TestApplication.subscriber = subscriber
                return object : NotificationSubscription {
                    override val id: String = "test"
                    override val topics: Set<String> = topics
                    override fun close() = Unit
                }
            }
        }

        /** 토큰은 무엇이든 계정 u1 — 프로젝트 둘이 같은 계정 id 를 갖는 상황. 검증이 도는 프로젝트를 기록한다 */
        @Bean
        fun verifier(): NotificationWebSocketTokenVerifier = NotificationWebSocketTokenVerifier {
            verifiedIn += ProjectContext.currentOrNull()?.id ?: "none"
            NotificationWebSocketPrincipal("u1")
        }
    }
}
