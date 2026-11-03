package dev.sumin.skeleton.videoworker

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import tools.jackson.databind.json.JsonMapper

/** 일꾼 API · 저장소를 한 서버로 흉내 낸다 — 시험이 일을 올려 두고 일꾼이 가져가 처리한 결과(complete · fail · 올린 파일)를 본다 */
class FakeVideoServer(val token: String = "test-token-" + "x".repeat(32)) : AutoCloseable {
    private val json = JsonMapper.builder().build()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val queue = ConcurrentLinkedQueue<Map<String, Any?>>()
    private val inputs = ConcurrentHashMap<String, Path>()

    /** 일꾼이 PUT 한 파일 — 경로(`/store/<jobId>/<이름>`) → 내용 · 헤더 */
    val stored = ConcurrentHashMap<String, ByteArray>()
    val storedHeaders = ConcurrentHashMap<String, Map<String, String>>()
    val completes = ConcurrentHashMap<String, String>()
    val fails = ConcurrentHashMap<String, String>()
    val rejectedAuth = java.util.concurrent.atomic.AtomicInteger()
    @Volatile var heartbeats = 0

    /** 일마다 heartbeat 본문의 progress 를 받은 순서대로 */
    val progresses = ConcurrentHashMap<String, java.util.concurrent.CopyOnWriteArrayList<Int>>()

    val base: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/api/internal/video/") { ex -> guard(ex) { api(ex) } }
        server.createContext("/store/") { ex -> guard(ex) { storage(ex) } }
        // 한 줄 처리로는 ffmpeg 의 범위 요청(탐색마다 새 연결)이 앞선 긴 응답에 막힌다
        server.executor = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r).also { it.isDaemon = true } }
        server.start()
    }

    /** 입력 파일을 `/store/in/<이름>` 으로 내놓는다 — 범위 요청을 받는다. 반환은 서명 주소 흉내 */
    fun serve(name: String, file: Path): String {
        inputs["/store/in/$name"] = file
        return "$base/store/in/$name"
    }

    /** 폴더째 내놓는다 — `/store/in/<이름>/<상대 경로>`. 반환은 폴더 안 [entry] 의 주소(조각 폴더 입력: `index.m3u8`) */
    fun serveTree(name: String, dir: Path, entry: String = "index.m3u8"): String {
        Files.walk(dir).use { walk -> walk.filter { Files.isRegularFile(it) }.forEach { inputs["/store/in/$name/${dir.relativize(it)}"] = it } }
        return "$base/store/in/$name/$entry"
    }

    fun enqueue(job: Map<String, Any?>) {
        queue.add(job)
    }

    fun job(jobId: String, lane: String, inputUrl: String, inputBytes: Long, extra: Map<String, Any?> = emptyMap()): Map<String, Any?> =
        mapOf("jobId" to jobId, "lane" to lane, "attempt" to 1, "leaseUntil" to "2026-10-05T12:00:00Z", "inputUrl" to inputUrl, "inputBytes" to inputBytes, "expectedOutputBytes" to 1_000_000, "verifyDecode" to false) + extra

    private fun api(ex: HttpExchange) {
        if (ex.requestHeaders.getFirst("Authorization") != "Bearer $token") {
            rejectedAuth.incrementAndGet()
            return reply(ex, 401, "{}")
        }
        val path = ex.requestURI.path.removePrefix("/api/internal/video")
        val body = ex.requestBody.readAllBytes().toString(Charsets.UTF_8)
        when {
            path == "/claim" -> {
                val job = queue.poll()
                if (job == null) reply(ex, 204, "") else reply(ex, 200, json.writeValueAsString(mapOf("value" to job)))
            }
            path.endsWith("/heartbeat") -> {
                heartbeats++
                json.readTree(body)["progress"]?.takeIf { it.isNumber }?.let { progresses.computeIfAbsent(path.split('/')[2]) { java.util.concurrent.CopyOnWriteArrayList() }.add(it.asInt()) }
                reply(ex, 200, """{"value":{"leaseUntil":"2026-10-05T12:10:00Z"}}""")
            }
            path.endsWith("/upload-urls") -> {
                val jobId = path.split('/')[2]
                val names = json.readTree(body)["names"].toList().map { it.asString() }
                val files = names.map { n ->
                    mapOf(
                        "name" to n, "method" to "PUT", "url" to "$base/store/$jobId/$n",
                        "headers" to mapOf("content-type" to "application/octet-stream", "cache-control" to "public, max-age=31536000, immutable"),
                        "expiresAt" to "2026-10-05T14:00:00Z",
                    )
                }
                reply(ex, 200, json.writeValueAsString(mapOf("values" to files)))
            }
            path.endsWith("/complete") -> { completes[path.split('/')[2]] = body; reply(ex, 204, "") }
            path.endsWith("/fail") -> { fails[path.split('/')[2]] = body; reply(ex, 204, "") }
            else -> reply(ex, 404, "{}")
        }
    }

    private fun storage(ex: HttpExchange) {
        val path = ex.requestURI.path
        when (ex.requestMethod) {
            "PUT" -> {
                stored[path] = ex.requestBody.readAllBytes()
                storedHeaders[path] = ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") }
                reply(ex, 200, "")
            }
            "GET", "HEAD" -> {
                val file = inputs[path] ?: return reply(ex, 404, "")
                val size = Files.size(file)
                val range = ex.requestHeaders.getFirst("Range")?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
                val (from, to) = if (range != null) range.groupValues[1].toLong() to (range.groupValues[2].toLongOrNull() ?: (size - 1)).coerceAtMost(size - 1) else 0L to size - 1
                ex.responseHeaders.add("Accept-Ranges", "bytes")
                if (range != null) ex.responseHeaders.add("Content-Range", "bytes $from-$to/$size")
                val length = to - from + 1
                ex.sendResponseHeaders(if (range != null) 206 else 200, if (ex.requestMethod == "HEAD") -1 else length)
                if (ex.requestMethod == "GET") {
                    // 클라이언트가 중간에 끊어도(ffprobe 는 앞부분만 읽고 닫는다) 서버는 계속 산다
                    runCatching {
                        Files.newInputStream(file).use { input ->
                            input.skipNBytes(from)
                            ex.responseBody.use { out ->
                                var left = length
                                val buffer = ByteArray(1 shl 16)
                                while (left > 0) {
                                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                                    if (n < 0) break
                                    out.write(buffer, 0, n)
                                    left -= n
                                }
                            }
                        }
                    }
                }
                ex.close()
            }
            else -> reply(ex, 405, "")
        }
    }

    private fun guard(ex: HttpExchange, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            runCatching { reply(ex, 500, e.toString()) }
        }
    }

    private fun reply(ex: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, if (status == 204 || bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty() && status != 204) ex.responseBody.use { it.write(bytes) }
        ex.close()
    }

    override fun close() = server.stop(0)
}
