package dev.sumin.skeleton.alert

import com.sun.net.httpserver.HttpServer
import dev.sumin.skeleton.common.time.TimeProvider
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import tools.jackson.databind.json.JsonMapper

class WebhookTest {
    private class Received(val path: String, val body: String)

    private val received = CopyOnWriteArrayList<Received>()
    @Volatile private var status = 204
    @Volatile private var redirectTo: String? = null
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            received += Received(exchange.requestURI.rawPath, exchange.requestBody.readBytes().decodeToString())
            val location = redirectTo
            if (location != null) {
                exchange.responseHeaders.add("Location", location)
                exchange.sendResponseHeaders(302, -1)
            } else {
                exchange.sendResponseHeaders(status, -1)
            }
            exchange.close()
        }
        start()
    }
    private val url get() = "http://127.0.0.1:${server.address.port}/api/webhooks/123/SECRET-TOKEN"

    @AfterTest fun stop() = server.stop(0)

    private val message = AlertMessage(
        kind = "JOB_DEAD", key = "send-mail", severity = AlertSeverity.ERROR, title = "A job is dead", detail = "job 7 stopped",
        suppressed = 3, occurredAt = Instant.parse("2026-10-06T00:00:00Z"), environment = "prod",
    )

    @Test
    fun `the webhook channel posts a Discord-style JSON card`() {
        WebhookAlertChannel(URI(url), Duration.ofSeconds(2)).send(message)

        val request = received.single()
        assertEquals("/api/webhooks/123/SECRET-TOKEN", request.path)
        val json = JsonMapper.builder().build().readTree(request.body)
        assertEquals("[prod][ERROR] A job is dead", json["content"].asString())
        val embed = json["embeds"][0]
        assertEquals("[prod][ERROR] A job is dead", embed["title"].asString())
        assertTrue(embed["description"].asString().contains("job 7 stopped"))
        assertTrue(embed["description"].asString().contains("3"), "the suppressed count is in the text")
        assertEquals(0xE67E22, embed["color"].asInt())
    }

    @Test
    fun `a failed delivery says the status but never the address that carries the secret`() {
        status = 500
        val ex = assertFailsWith<AlertDeliveryException> { WebhookAlertChannel(URI(url), Duration.ofSeconds(2)).send(message) }
        assertTrue(ex.message!!.contains("500"), ex.message)
        assertFalse(ex.message!!.contains("SECRET-TOKEN"), ex.message)
    }

    @Test
    fun `redirects are not followed`() {
        redirectTo = "http://127.0.0.1:${server.address.port}/elsewhere"
        assertFailsWith<AlertDeliveryException> { WebhookAlertChannel(URI(url), Duration.ofSeconds(2)).send(message) }
        assertEquals(1, received.size)
    }

    @Test
    fun `when recording is down a direct-fallback kind still reaches the webhook once per interval`() {
        val clock = object : TimeProvider { var now = Instant.parse("2026-10-06T00:00:00Z"); override fun now() = now }
        val brokenStore = object : AlertStore {
            override fun record(kind: AlertKind, key: String, severity: AlertSeverity, title: String, detail: String, now: Instant, minInterval: Duration): AlertRecorded = error("db down")
        }
        val alerts = DefaultOwnerAlerts(brokenStore, emptyList(), AlertProperties(webhookUrl = url), clock, handoff = { it.run() }, pause = {})

        alerts.emit(BuiltInAlertKind.SERVER_ERROR_SURGE, detail = "25 errors in 60s")
        alerts.emit(BuiltInAlertKind.SERVER_ERROR_SURGE, detail = "again")
        assertEquals(1, received.size, "the second one is inside the in-memory interval")
        assertTrue(received.single().body.contains("25 errors in 60s"))

        clock.now = clock.now.plus(Duration.ofMinutes(16))
        alerts.emit(BuiltInAlertKind.SERVER_ERROR_SURGE, detail = "later")
        assertEquals(2, received.size)
    }

    @Test
    fun `a kind that is not direct-fallback is not sent when recording is down`() {
        val brokenStore = object : AlertStore {
            override fun record(kind: AlertKind, key: String, severity: AlertSeverity, title: String, detail: String, now: Instant, minInterval: Duration): AlertRecorded = error("db down")
        }
        DefaultOwnerAlerts(brokenStore, emptyList(), AlertProperties(webhookUrl = url), TimeProvider.systemUtc(), handoff = { it.run() }, pause = {})
            .emit(BuiltInAlertKind.JOB_DEAD, "x")
        assertTrue(received.isEmpty())
    }

    @Test
    fun `startup failure goes straight to the webhook, skips configured profiles, and masks the message`() {
        val sent = StartupFailureAlert.attempt(
            webhookUrl = url, profiles = listOf("prod"), skipProfiles = listOf("local"),
            message = "Failed to bind properties: password=hunter2 under 'skeleton.x'",
        )
        assertTrue(sent)
        val body = received.single().body
        assertTrue(body.contains("[prod][CRITICAL]"), body)
        assertFalse(body.contains("hunter2"), body)

        assertFalse(StartupFailureAlert.attempt(webhookUrl = url, profiles = listOf("local"), skipProfiles = listOf("local"), message = "x"))
        assertFalse(StartupFailureAlert.attempt(webhookUrl = "", profiles = listOf("prod"), skipProfiles = listOf("local"), message = "x"))
        assertEquals(1, received.size)
    }
}
