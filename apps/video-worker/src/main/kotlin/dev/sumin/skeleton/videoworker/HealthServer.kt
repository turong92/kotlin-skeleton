package dev.sumin.skeleton.videoworker

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/**
 * 상주(poll) 모양의 작은 `/health` — 홈서버 배포 계약이 컨테이너 안에서 `wget` 으로 부르는 자리. 일꾼이 살아 있으면 200.
 * 일 처리와 따로 도는 스레드라 긴 변환 중에도 응답한다. 이 서버는 다른 일을 하지 않는다
 */
class HealthServer private constructor(private val server: HttpServer) : AutoCloseable {
    val port: Int get() = server.address.port

    override fun close() = server.stop(0)

    companion object {
        fun start(port: Int): HealthServer {
            val server = HttpServer.create(InetSocketAddress(port), 0)
            server.createContext("/") { ex -> handle(ex) }
            server.executor = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "health").also { it.isDaemon = true } }
            server.start()
            return HealthServer(server)
        }

        private fun handle(ex: HttpExchange) = ex.use {
            val (status, body) = when {
                ex.requestURI.path != "/health" -> 404 to ""
                ex.requestMethod != "GET" && ex.requestMethod != "HEAD" -> 405 to ""
                else -> 200 to """{"status":"UP"}"""
            }
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, if (bytes.isEmpty() || ex.requestMethod == "HEAD") -1 else bytes.size.toLong())
            if (bytes.isNotEmpty() && ex.requestMethod != "HEAD") ex.responseBody.write(bytes)
        }
    }
}
