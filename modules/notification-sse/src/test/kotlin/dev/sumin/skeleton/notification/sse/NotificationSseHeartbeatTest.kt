package dev.sumin.skeleton.notification.sse

import dev.sumin.skeleton.notification.NotificationSubscriber
import dev.sumin.skeleton.notification.NotificationSubscription
import dev.sumin.skeleton.notification.NotificationSubscriptionRegistry
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class NotificationSseHeartbeatTest {
    private class Registry : NotificationSubscriptionRegistry {
        val closed = mutableListOf<String>()
        private var seq = 0

        override fun subscribe(topics: Set<String>, subscriber: NotificationSubscriber): NotificationSubscription {
            val id = "sub-${++seq}"
            return object : NotificationSubscription {
                override val id: String = id
                override val topics: Set<String> = topics
                override fun close() {
                    closed += id
                }
            }
        }
    }

    private fun heartbeatThreads() = Thread.getAllStackTraces().keys.filter { it.name == NotificationSseService.HEARTBEAT_THREAD && it.isAlive }

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not met in time" }
            Thread.sleep(20)
        }
    }

    private fun comments(result: MvcResult) = result.response.contentAsString.lines().count { it == ":" }

    @Test
    fun `off by default - start spawns no thread and sends no comment`() {
        val service = NotificationSseService(Registry(), NotificationSseProperties())
        val mvc = MockMvcBuilders.standaloneSetup(NotificationSseController(service)).build()
        service.start()
        try {
            val result = mvc.perform(get("/api/v1/notifications/sse")).andReturn()
            Thread.sleep(200)

            assertTrue(heartbeatThreads().isEmpty())
            assertEquals(0, comments(result))
        } finally {
            service.stop()
        }
    }

    @Test
    fun `on - a comment line reaches an open connection on the interval and stop shuts the thread down`() {
        val service = NotificationSseService(Registry(), NotificationSseProperties(heartbeat = Duration.ofMillis(50)))
        val mvc = MockMvcBuilders.standaloneSetup(NotificationSseController(service)).build()
        service.start()
        try {
            val result = mvc.perform(get("/api/v1/notifications/sse")).andReturn()

            awaitUntil { comments(result) >= 2 }
            assertEquals(1, heartbeatThreads().size)
            assertTrue(result.response.contentAsString.startsWith("event:connected"), "the connected event still comes first")
        } finally {
            service.stop()
        }
        awaitUntil { heartbeatThreads().isEmpty() }
    }

    @Test
    fun `a connection whose send fails is dropped and its subscription closed, the others keep their heartbeat`() {
        val registry = Registry()
        val service = NotificationSseService(registry, NotificationSseProperties(heartbeat = Duration.ofMillis(50)))
        val mvc = MockMvcBuilders.standaloneSetup(NotificationSseController(service)).build()
        val dead = service.connect(listOf("a"))
        dead.complete()   // 이미 끝난 연결 - 다음 send 가 예외를 던진다
        registry.closed.clear()
        service.start()
        try {
            val alive = mvc.perform(get("/api/v1/notifications/sse")).andReturn()

            awaitUntil { service.openConnections == 1 }
            assertFalse(registry.closed.contains("sub-2"), "the healthy connection stays subscribed")
            awaitUntil { comments(alive) >= 2 }
        } finally {
            service.stop()
        }
    }

    @Test
    fun `heartbeat must be positive`() {
        assertFailsWith<IllegalArgumentException> { NotificationSseProperties(heartbeat = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { NotificationSseProperties(heartbeat = Duration.ofSeconds(-1)) }
        assertNull(NotificationSseProperties().heartbeat)
    }
}
