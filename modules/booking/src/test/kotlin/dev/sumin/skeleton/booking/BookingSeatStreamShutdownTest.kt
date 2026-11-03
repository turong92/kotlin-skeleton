package dev.sumin.skeleton.booking

import dev.sumin.skeleton.booking.web.BookingSeatStreamService
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * 종료 (스펙 §7 시험 11): 열린 배치도 흐름이 있어도 서버 종료가 30초를 기다리지 않는다 - 종료가 시작되면 웹 서버의 종료 단계보다 먼저 연결을 모두 닫는다(`notification-sse` 선례).
 * 같은 설정으로 흐름의 첫 사건이 `ready` 이고 그 뒤 자리 사건이 온다는 것도 본다.
 */
class BookingSeatStreamShutdownTest {
    private class Subscriptions(private val relay: LocalBookingSeatRelay) : BookingSeatRelay by relay {
        val closed = AtomicInteger()

        override fun subscribe(poolId: String, listener: BookingSeatListener): AutoCloseable {
            val inner = relay.subscribe(poolId, listener)
            return AutoCloseable { closed.incrementAndGet(); inner.close() }
        }
    }

    @RestController
    private class StreamController(private val streams: BookingSeatStreamService) {
        @GetMapping("/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
        fun stream(): SseEmitter = streams.connect("pool-1")
    }

    private val local = LocalBookingSeatRelay()
    private val relay = Subscriptions(local)

    private fun mvc(service: BookingSeatStreamService): MockMvc = MockMvcBuilders.standaloneSetup(StreamController(service)).build()

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not met in time" }
            Thread.sleep(20)
        }
    }

    @Test
    fun `stopping the application closes every open stream and its subscription at once, and a stream that arrives after stop is closed too`() {
        val service = BookingSeatStreamService(relay)
        service.start()
        val web = mvc(service)
        web.perform(get("/stream")).andReturn()
        web.perform(get("/stream")).andReturn()
        assertEquals(2, service.openConnections)

        val started = System.nanoTime()
        service.stop()
        val tookMillis = Duration.ofNanos(System.nanoTime() - started).toMillis()

        assertEquals(0, service.openConnections)
        assertEquals(2, relay.closed.get(), "every subscription is closed")
        assertTrue(tookMillis < 5_000, "closing is immediate, not the 30 second graceful wait ($tookMillis ms)")
        assertFalse(service.isRunning)
        assertTrue(service.phase > 2147482623, "must stop before the web server's graceful shutdown phase")

        web.perform(get("/stream")).andReturn()
        assertEquals(3, relay.closed.get(), "a connection that arrives after stop does not hold the shutdown")
        local.destroy()
    }

    @Test
    fun `a stream says ready first, then carries the seat events of its pool only`() {
        val service = BookingSeatStreamService(relay)
        service.start()
        try {
            val result = mvc(service).perform(get("/stream")).andReturn()

            local.publish("other-pool", listOf(SeatChange("bks_other", SeatState.TAKEN, null, 1)))
            local.publish("pool-1", listOf(SeatChange("bks_a", SeatState.TAKEN, Instant.parse("2026-11-03T03:10:00Z"), 2), SeatChange("bks_b", SeatState.OPEN, null, 5)))
            awaitUntil { result.response.contentAsString.contains("bks_b") && result.response.contentAsString.trimEnd().endsWith("}") }

            val body = result.response.contentAsString
            assertTrue(body.startsWith("event:ready"), "ready comes first: $body")
            assertTrue("bks_a" in body && "bks_b" in body && "bks_other" !in body, "only this pool's seats: $body")
            assertTrue("\"v\":2" in body && "2026-11-03T03:10:00Z" in body, "carries the version and the deadline: $body")
        } finally {
            service.stop()
            local.destroy()
        }
    }
}
