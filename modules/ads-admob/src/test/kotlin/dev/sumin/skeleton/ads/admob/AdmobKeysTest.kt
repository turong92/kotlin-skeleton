package dev.sumin.skeleton.ads.admob

import com.sun.net.httpserver.HttpServer
import dev.sumin.skeleton.adstest.FakeKeySource
import dev.sumin.skeleton.adstest.MutableTime
import dev.sumin.skeleton.adstest.newEcKeyPair
import java.net.InetSocketAddress
import java.time.Duration
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AdmobKeysTest {
    private val time = MutableTime()
    private val source = FakeKeySource()
    private val first = newEcKeyPair()
    private val second = newEcKeyPair()
    private val keys = CachingAdmobVerifierKeys(source, time, ttl = Duration.ofHours(24))

    @Test
    fun `keys are read once and kept while they are fresh`() {
        source.keys = mapOf("1" to first.public)

        assertNotNull(keys.find("1"))
        time.now = time.now.plus(Duration.ofHours(23))
        assertNotNull(keys.find("1"))

        assertEquals(1, source.loads.get())
    }

    @Test
    fun `keys older than the ttl are read again`() {
        source.keys = mapOf("1" to first.public)
        keys.find("1")

        time.now = time.now.plus(Duration.ofHours(24))
        source.keys = mapOf("1" to second.public)

        assertEquals(second.public, keys.find("1"))
        assertEquals(2, source.loads.get())
    }

    @Test
    fun `an unknown key id re-reads the file once and finds a rotated-in key`() {
        source.keys = mapOf("1" to first.public)
        keys.find("1")

        time.now = time.now.plus(Duration.ofMinutes(5))
        source.keys = mapOf("1" to first.public, "2" to second.public)

        assertEquals(second.public, keys.find("2"))
        assertEquals(2, source.loads.get())
    }

    @Test
    fun `random unknown key ids cannot make us re-read the file on every request`() {
        source.keys = mapOf("1" to first.public)
        keys.find("1")

        time.now = time.now.plus(Duration.ofMinutes(5))
        assertNull(keys.find("x"))
        assertNull(keys.find("y"))
        assertNull(keys.find("z"))

        assertEquals(2, source.loads.get())
    }

    @Test
    fun `a key file that cannot be fetched is reported as unavailable, not as an unknown key`() {
        source.failing = true

        assertFailsWith<AdmobKeysUnavailableException> { keys.find("1") }
    }

    @Test
    fun `a failed read also counts for the one-minute floor`() {
        source.failing = true
        assertFailsWith<AdmobKeysUnavailableException> { keys.find("1") }
        assertFailsWith<AdmobKeysUnavailableException> { keys.find("1") }
        assertEquals(1, source.loads.get())

        time.now = time.now.plus(Duration.ofMinutes(2))
        source.failing = false
        source.keys = mapOf("1" to first.public)
        assertEquals(first.public, keys.find("1"))
    }

    @Test
    fun `the ttl cannot be longer than a day`() {
        assertFailsWith<IllegalArgumentException> { AdmobProperties(keyCacheTtl = Duration.ofHours(25)) }
        assertFailsWith<IllegalArgumentException> { AdmobProperties(keyCacheTtl = Duration.ZERO) }
    }

    @Test
    fun `the http source reads google's verifier-keys json format`() {
        val der = Base64.getEncoder().encodeToString(first.public.encoded)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/keys") { exchange ->
                val body = """{"keys":[{"keyId":3335741209,"pem":"unused","base64":"$der"}]}""".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            createContext("/moved") { exchange -> exchange.responseHeaders.add("Location", "/keys"); exchange.sendResponseHeaders(301, -1); exchange.close() }
            createContext("/broken") { exchange -> exchange.sendResponseHeaders(500, -1); exchange.close() }
            start()
        }
        try {
            val base = "http://127.0.0.1:${server.address.port}"

            assertEquals(first.public, HttpAdmobKeySource("$base/keys", Duration.ofSeconds(2)).load()["3335741209"])
            assertEquals(first.public, HttpAdmobKeySource("$base/moved", Duration.ofSeconds(2)).load()["3335741209"])   // 301 을 따라간다
            assertFailsWith<AdmobKeysUnavailableException> { HttpAdmobKeySource("$base/broken", Duration.ofSeconds(2)).load() }
        } finally {
            server.stop(0)
        }
    }
}
