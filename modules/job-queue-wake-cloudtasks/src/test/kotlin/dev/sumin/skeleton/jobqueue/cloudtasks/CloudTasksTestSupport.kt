package dev.sumin.skeleton.jobqueue.cloudtasks

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.sumin.skeleton.common.time.TimeProvider
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

private val json = JsonMapper.builder().build()

fun jsonOf(text: String): JsonNode = json.readTree(text)

class MutableTime(var now: Instant = Instant.parse("2026-11-18T00:00:00Z")) : TimeProvider {
    override fun now(): Instant = now
}

/** 진짜 소켓 위의 가짜 메타데이터 서버 + Cloud Tasks API - 받은 요청을 모두 기록한다 */
class FakeGoogle : AutoCloseable {
    class Received(val path: String, val authorization: String?, val flavor: String?, val body: String)

    val tokenRequests = CopyOnWriteArrayList<Received>()
    val taskRequests = CopyOnWriteArrayList<Received>()
    @Volatile var taskStatus = 200
    @Volatile var tokenStatus = 200
    @Volatile var tokenExpiresIn = 3600
    @Volatile var taskDelayMillis = 0L

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/computeMetadata/") { ex -> handle(ex, tokenRequests, tokenStatus) { """{"access_token":"fake-access-token-${tokenRequests.size}","expires_in":$tokenExpiresIn,"token_type":"Bearer"}""" } }
        executor = java.util.concurrent.Executors.newCachedThreadPool()   // 겹친 요청을 동시에 받는다
        createContext("/v2/") { ex -> if (taskDelayMillis > 0) Thread.sleep(taskDelayMillis); handle(ex, taskRequests, taskStatus) {"""{"name":"projects/acme-proj/locations/asia-northeast3/queues/acme-drain/tasks/t1"}""" } }
        start()
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    private fun handle(ex: HttpExchange, into: MutableList<Received>, status: Int, body: () -> String) {
        into += Received(ex.requestURI.path, ex.requestHeaders.getFirst("Authorization"), ex.requestHeaders.getFirst("Metadata-Flavor"), ex.requestBody.readAllBytes().toString(Charsets.UTF_8))
        val bytes = (if (status in 200..299) body() else """{"error":"refused"}""").toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    override fun close() = server.stop(0)
}
