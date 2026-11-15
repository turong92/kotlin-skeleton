package dev.sumin.skeleton.alert.client

import dev.sumin.skeleton.alert.AlertKind
import dev.sumin.skeleton.alert.AlertProperties
import dev.sumin.skeleton.alert.BuiltInAlertKind
import dev.sumin.skeleton.alert.OwnerAlerts
import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.web.InMemoryFixedWindowRateLimitStore
import java.io.ByteArrayInputStream
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientErrorIntakeTest {
    private class Emitted(val kind: AlertKind, val key: String, val detail: String)

    private class Capturing : OwnerAlerts {
        val emitted = CopyOnWriteArrayList<Emitted>()
        override fun emit(kind: AlertKind, key: String, detail: String, immediate: Boolean) {
            emitted += Emitted(kind, key, detail)
        }
    }

    private class Clock(var now: Instant = Instant.parse("2026-11-16T00:00:00Z")) : TimeProvider {
        override fun now() = now
    }

    private val alerts = Capturing()
    private val clock = Clock()
    private val received = CopyOnWriteArrayList<ClientErrorReport>()

    private fun intake(settings: AlertProperties.ClientErrors = AlertProperties.ClientErrors(enabled = true)) = ClientErrorIntake(
        settings,
        InMemoryFixedWindowRateLimitStore(java.time.Clock.fixed(clock.now, java.time.ZoneOffset.UTC)),
        clock,
        ClientErrorSurge(alerts, settings.surge, clock, handoff = { it.run() }),
        sink = { received += it },
    )

    private fun ClientErrorIntake.send(json: String, client: String = "ip:198.51.100.1", agent: String? = "TestAgent/1.0") {
        val bytes = json.toByteArray()
        receive(client, agent, bytes.size.toLong(), ByteArrayInputStream(bytes))
    }

    private fun codeOf(block: () -> Unit) = assertFailsWith<ApplicationException>(block = block).errorCode.code

    @Test
    fun `a report is kept with the fields the owner needs to follow it to the server log`() {
        intake().send(
            """{"platform":"web","source":"window.onerror","name":"TypeError","message":"x is undefined","stack":"TypeError: x is undefined\n    at f (app.js:1:2)",
               "route":"/orders/42","release":"1.4.2","traceId":"0af7651916cd43dd8448eb211c80319c","count":3}""",
        )
        val r = received.single()
        assertEquals("web", r.platform)
        assertEquals("window.onerror", r.source)
        assertEquals("TypeError", r.name)
        assertEquals("x is undefined", r.message)
        assertTrue(r.stack!!.contains("at f (app.js:1:2)"))
        assertEquals("/orders/42", r.route)
        assertEquals("1.4.2", r.release)
        assertEquals("0af7651916cd43dd8448eb211c80319c", r.clientTraceId)
        assertEquals(3, r.count)
        assertEquals("TestAgent/1.0", r.userAgent)
    }

    @Test
    fun `every field is cut to its limit and the route loses its query and fragment`() {
        val long = "a".repeat(5000)
        intake().send("""{"platform":"${"p".repeat(100)}","message":"$long","stack":"$long","componentStack":"$long","route":"/a/${"b".repeat(500)}?token=1#x","release":"${"r".repeat(200)}","count":999999}""", agent = "u".repeat(900))
        val r = received.single()
        assertEquals(32, r.platform!!.length)
        assertEquals(500, r.message.length)
        assertEquals(4000, r.stack!!.length)
        assertEquals(1500, r.componentStack!!.length)
        assertEquals(200, r.route!!.length)
        assertFalse(r.route.contains('?') || r.route.contains('#'))
        assertEquals(64, r.release!!.length)
        assertEquals(1000, r.count)
        assertEquals(200, r.userAgent!!.length)
    }

    @Test
    fun `tokens, emails and url queries are removed before anything is logged or alerted`() {
        intake().send(
            """{"message":"fetch https://api.example.com/x?access_token=SECRET1&a=b failed for Bearer abc.def-ghi, mail me@example.com token=SECRET2 eyJhbGciOi.eyJzdWIiOi.c2lnbmF0dXJl",
               "stack":"at https://app.example.com/cb?code=SECRET3#frag (x.js:1:1)","route":"/reset/SECRET4?token=SECRET5"}""",
        )
        val r = received.single()
        val all = listOf(r.message, r.stack, r.route).joinToString("\n")
        for (leaked in listOf("SECRET1", "SECRET2", "SECRET3", "SECRET5", "abc.def-ghi", "me@example.com", "eyJhbGciOi", "c2lnbmF0dXJl")) {
            assertFalse(all.contains(leaked), "$leaked leaked: $all")
        }
        assertTrue(r.message.contains("https://api.example.com/x"), r.message)
    }

    @Test
    fun `line breaks cannot forge a log line, and a trace id that is not 32 hex is dropped`() {
        intake().send("""{"platform":"web\nINFO fake","message":"boom\r\nERROR forged line","name":"E\nF","route":"/a\nb","release":"1\n2","traceId":"not-a-trace"}""")
        val r = received.single()
        for (v in listOf(r.platform, r.message, r.name, r.route, r.release)) assertFalse(v!!.contains('\n') || v.contains('\r'), v)
        assertNull(r.clientTraceId)
    }

    @Test
    fun `values of the wrong type are ignored, a missing message is a bad request`() {
        intake().send("""{"message":"ok","stack":{"a":1},"count":"many","route":["x"]}""")
        val r = received.single()
        assertNull(r.stack)
        assertNull(r.route)
        assertEquals(1, r.count)
        assertEquals("CLIENT_ERROR.INVALID", codeOf { intake().send("""{"name":"E"}""") })
        assertEquals("CLIENT_ERROR.INVALID", codeOf { intake().send("""["not","an","object"]""") })
        assertEquals("CLIENT_ERROR.INVALID", codeOf { intake().send("not json") })
        assertEquals(1, received.size)
    }

    @Test
    fun `a body over the limit is refused, whether the length was declared or not`() {
        val settings = AlertProperties.ClientErrors(enabled = true, maxBodyBytes = 200)
        val intake = intake(settings)
        val json = """{"message":"${"m".repeat(300)}"}"""
        assertEquals("CLIENT_ERROR.TOO_LARGE", codeOf { intake.send(json) })
        assertEquals("CLIENT_ERROR.TOO_LARGE", codeOf { intake.receive("ip:1", null, -1, ByteArrayInputStream(json.toByteArray())) })
        assertTrue(received.isEmpty())
    }

    @Test
    fun `one client gets a few reports per window, another client is not affected`() {
        val intake = intake(AlertProperties.ClientErrors(enabled = true, perClient = 2))
        intake.send("""{"message":"a"}""")
        intake.send("""{"message":"b"}""")
        val e = assertFailsWith<ApplicationException> { intake.send("""{"message":"c"}""") }
        assertEquals("CLIENT_ERROR.TOO_MANY", e.errorCode.code)
        assertTrue((e.data as Map<*, *>)["retryAfterSeconds"] is Long)
        intake.send("""{"message":"d"}""", client = "ip:203.0.113.9")
        assertEquals(listOf("a", "b", "d"), received.map { it.message })
    }

    @Test
    fun `a refused body still costs the caller a turn, so garbage cannot be retried for free`() {
        val intake = intake(AlertProperties.ClientErrors(enabled = true, perClient = 1))
        assertEquals("CLIENT_ERROR.INVALID", codeOf { intake.send("not json") })
        assertEquals("CLIENT_ERROR.TOO_MANY", codeOf { intake.send("""{"message":"a"}""") })
    }

    @Test
    fun `reports at the threshold inside the window raise one folded alert, with the latest one in it`() {
        val intake = intake(AlertProperties.ClientErrors(enabled = true, perClient = 100, surge = AlertProperties.Surge(threshold = 3, window = Duration.ofMinutes(5))))
        intake.send("""{"message":"first","platform":"web"}""")
        intake.send("""{"message":"second","platform":"web"}""")
        assertTrue(alerts.emitted.isEmpty())
        intake.send("""{"message":"third boom","platform":"android","release":"2.0.1","route":"/pay","traceId":"0af7651916cd43dd8448eb211c80319c"}""")
        val e = alerts.emitted.single()
        assertEquals(BuiltInAlertKind.CLIENT_ERROR_SURGE, e.kind)
        for (part in listOf("3 client errors", "third boom", "android", "2.0.1", "/pay", "0af7651916cd43dd8448eb211c80319c")) assertTrue(e.detail.contains(part), "${e.detail} lacks $part")
    }

    @Test
    fun `reports older than the window do not add up to an alert`() {
        val intake = intake(AlertProperties.ClientErrors(enabled = true, perClient = 100, surge = AlertProperties.Surge(threshold = 2, window = Duration.ofMinutes(5))))
        intake.send("""{"message":"a"}""")
        clock.now = clock.now.plusSeconds(301)
        intake.send("""{"message":"b"}""")
        assertTrue(alerts.emitted.isEmpty())
    }
}
