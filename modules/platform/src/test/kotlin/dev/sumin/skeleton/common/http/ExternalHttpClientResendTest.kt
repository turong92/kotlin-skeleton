package dev.sumin.skeleton.common.http

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.HttpResources

/**
 * 실측: `DefaultExternalHttpClient` 는 연결이 응답 없이 끊겼을 때 POST 를 스스로 다시 보내는가.
 * 날 소켓 서버가 요청을 읽고(요청 수를 센다) 응답 없이 연결을 끊는다. 결제 호출처럼 "다시 보내면 안 되는" 호출의 전제를 고정한다.
 * 서버는 127.0.0.1 에만 묶는다(`TestServerRulesTest`).
 */
class ExternalHttpClientResendTest {
    private lateinit var server: ServerSocket
    private val requestsSeen = AtomicInteger()
    private val connectionsSeen = AtomicInteger()

    /** 연결마다 몇 번째 요청인지(1부터)를 받아 어떻게 끝낼지 정한다. */
    @Volatile
    private var behaviour: (connectionNo: Int, requestNoOnConnection: Int) -> Action = { _, _ -> Action.CLOSE }
    private val sockets = Collections.synchronizedList(mutableListOf<Socket>())
    private lateinit var client: ExternalHttpClient

    private enum class Action { ANSWER_200, CLOSE, RESET }

    @BeforeTest
    fun setUp() {
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                sockets += socket
                val connectionNo = connectionsSeen.incrementAndGet()
                Thread { serve(socket, connectionNo) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
        client = DefaultExternalHttpClient(
            webClientBuilder = WebClient.builder(),
            properties = OutboundHttpProperties(
                defaultConnectTimeout = Duration.ofSeconds(30),
                defaultResponseTimeout = Duration.ofSeconds(30),
                clients = mapOf("pay" to OutboundHttpProperties.Client(baseUrl = "http://127.0.0.1:${server.localPort}")),
            ),
            defaultErrorMapper = DefaultExternalHttpErrorMapper(),
            customizers = emptyList(),
        )
    }

    @AfterTest
    fun tearDown() {
        runCatching { server.close() }
        sockets.forEach { runCatching { it.close() } }
    }

    private fun serve(socket: Socket, connectionNo: Int) {
        var requestNo = 0
        val input = socket.getInputStream()
        while (readOneRequest(input)) {
            requestNo++
            requestsSeen.incrementAndGet()
            when (behaviour(connectionNo, requestNo)) {
                Action.ANSWER_200 -> {
                    val body = "{}"
                    socket.getOutputStream().write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\n\r\n$body").toByteArray(),
                    )
                    socket.getOutputStream().flush()
                }
                Action.CLOSE -> {
                    runCatching { socket.close() }
                    return
                }
                Action.RESET -> {
                    runCatching { socket.setSoLinger(true, 0) }
                    runCatching { socket.close() }
                    return
                }
            }
        }
    }

    /** 헤더 끝과 Content-Length 만큼의 본문을 읽는다. 연결이 닫혀 있으면 false. */
    private fun readOneRequest(input: java.io.InputStream): Boolean {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = runCatching { input.read() }.getOrDefault(-1)
            if (b < 0) return false
            head.append(b.toChar())
        }
        val length = Regex("(?i)content-length:\\s*(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
        repeat(length) { if (input.read() < 0) return false }
        return true
    }

    private fun post() = client.post("pay", "/pay", mapOf("amount" to 1_000), Map::class.java).block()

    /**
     * `block()` returns when the answer is complete, but Reactor Netty hands the connection back to its pool on the event loop
     * a moment later; a second call made in between opens a new connection instead of reusing this one. The release runs on the
     * event loop thread, so once every loop has run a task queued after it, the connection is idle in the pool.
     */
    private fun awaitConnectionBackInPool() {
        HttpResources.get().onClient(false).forEach { loop -> loop.submit {}.get(10, TimeUnit.SECONDS) }
    }

    @Test
    fun `a POST on a fresh connection that is closed without an answer is sent once`() {
        behaviour = { _, _ -> Action.CLOSE }

        assertFailsWith<ExternalHttpNetworkException> { post() }

        assertEquals(1, requestsSeen.get(), "requests the server read: ${requestsSeen.get()} on ${connectionsSeen.get()} connection(s)")
    }

    @Test
    fun `a POST on a fresh connection that is reset is sent once`() {
        behaviour = { _, _ -> Action.RESET }

        assertFailsWith<ExternalHttpNetworkException> { post() }

        assertEquals(1, requestsSeen.get(), "requests the server read: ${requestsSeen.get()} on ${connectionsSeen.get()} connection(s)")
    }

    @Test
    fun `a POST on a reused connection that is closed without an answer is sent once`() {
        behaviour = { _, requestNo -> if (requestNo == 1) Action.ANSWER_200 else Action.CLOSE }
        post()
        awaitConnectionBackInPool()
        requestsSeen.set(0)
        connectionsSeen.set(0)
        behaviour = { _, _ -> Action.CLOSE }

        assertFailsWith<ExternalHttpNetworkException> { post() }

        assertEquals(0, connectionsSeen.get(), "the second call must have gone over the already open connection")
        assertEquals(1, requestsSeen.get(), "requests the server read: ${requestsSeen.get()} on ${connectionsSeen.get()} connection(s)")
    }
}
