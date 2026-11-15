package dev.sumin.skeleton.common

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `scripts/post-deploy-check.sh` — 배포된 주소의 `/health` 와 로그인 한 번을 확인한다 (docs/deploy.md 「배포 후 자동 점검」).
 * 진짜 HTTP 서버 하나(JDK)를 세워 스크립트를 실제로 돌린다.
 */
class PostDeployCheckScriptTest {
    private val repoRoot: Path = Path.of(requireNotNull(System.getProperty("skeleton.repoRoot")) { "skeleton.repoRoot system property" })

    private val password = "pw-S3cret-value"
    private val accessToken = "AT-token-value-123"
    private val headerValue = "tag-secret-value"

    /** /health 가 몇 번째 호출부터 200 인가 (1 = 처음부터) */
    private val healthOkFrom = AtomicInteger(1)
    private val healthCalls = AtomicInteger(0)
    private val seenHeaders = CopyOnWriteArrayList<String>()
    private val hits = ConcurrentHashMap<String, Int>()

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            hits.merge(ex.requestURI.path, 1, Int::plus)
            ex.requestHeaders["X-Revision-Tag"]?.let { seenHeaders += "${ex.requestURI.path}=${it.first()}" }
            when (ex.requestURI.path) {
                "/health" -> if (healthCalls.incrementAndGet() >= healthOkFrom.get()) ex.reply(200, """{"status":"UP"}""") else ex.reply(503, "")
                "/api/v1/auth/login" -> {
                    val body = ex.requestBody.readBytes().decodeToString()
                    if (password in body && "smoke@example.com" in body) ex.reply(200, """{"value":{"accessToken":"$accessToken","refreshToken":"RT-1"}}""")
                    else ex.reply(401, """{"code":"AUTH.INVALID_CREDENTIALS"}""")
                }
                "/api/v1/auth/me" -> if (ex.requestHeaders.getFirst("Authorization") == "Bearer $accessToken") ex.reply(200, """{"value":{"email":"smoke@example.com"}}""") else ex.reply(401, "")
                "/api/v1/auth/logout" -> ex.reply(204, "")
                else -> ex.reply(404, "")
            }
        }
        start()
    }
    private val url = "http://127.0.0.1:${server.address.port}"

    @AfterTest fun stop() = server.stop(0)

    private fun HttpExchange.reply(status: Int, body: String) {
        val bytes = body.toByteArray()
        sendResponseHeaders(status, if (status == 204) -1 else bytes.size.toLong())
        if (status != 204) responseBody.use { it.write(bytes) }
        close()
    }

    private class Result(val code: Int, val out: String)

    private fun deploy(vararg args: String, env: Map<String, String> = mapOf("SMOKE_EMAIL" to "smoke@example.com", "SMOKE_PASSWORD" to password)): Result {
        val pb = ProcessBuilder(listOf("bash", repoRoot.resolve("scripts/post-deploy-check.sh").toString()) + args).redirectErrorStream(true)
        pb.environment().remove("SMOKE_EMAIL"); pb.environment().remove("SMOKE_PASSWORD")
        pb.environment().putAll(env)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(60, TimeUnit.SECONDS)) { "post-deploy-check.sh timed out" }
        return Result(p.exitValue(), out)
    }

    @Test
    fun `a healthy deployment passes - health, login, me, and the extra header goes to every call`() {
        val r = deploy(url, "-H", "X-Revision-Tag: $headerValue", "--wait", "0")
        assertEquals(0, r.code, r.out)
        assertEquals(1, hits["/health"]); assertEquals(1, hits["/api/v1/auth/login"]); assertEquals(1, hits["/api/v1/auth/me"])
        assertEquals(setOf("/health", "/api/v1/auth/login", "/api/v1/auth/me", "/api/v1/auth/logout"), seenHeaders.map { it.substringBefore("=") }.toSet())
        assertTrue(seenHeaders.all { it.endsWith("=$headerValue") })
    }

    @Test
    fun `secrets are never printed - password, token, header value`() {
        val ok = deploy(url, "-H", "X-Revision-Tag: $headerValue", "--wait", "0")
        assertEquals(0, ok.code, ok.out)
        val bad = deploy(url, "--wait", "0", env = mapOf("SMOKE_EMAIL" to "smoke@example.com", "SMOKE_PASSWORD" to "wrong-S3cret"))
        assertEquals(1, bad.code, bad.out)
        for (text in listOf(ok.out, bad.out)) for (secret in listOf(password, "wrong-S3cret", accessToken, headerValue)) assertFalse(secret in text, "$secret leaked: $text")
    }

    @Test
    fun `an unhealthy deployment fails and does not try to log in`() {
        healthOkFrom.set(Int.MAX_VALUE)
        val r = deploy(url, "--wait", "0")
        assertEquals(1, r.code, r.out)
        assertTrue("/health" in r.out && "503" in r.out, r.out)
        assertEquals(null, hits["/api/v1/auth/login"])
    }

    @Test
    fun `health is retried until the wait runs out, so a cold start passes`() {
        healthOkFrom.set(3)
        val r = deploy(url, "--wait", "20")
        assertEquals(0, r.code, r.out)
        assertEquals(3, healthCalls.get())
    }

    @Test
    fun `a wrong password fails`() {
        val r = deploy(url, "--wait", "0", env = mapOf("SMOKE_EMAIL" to "smoke@example.com", "SMOKE_PASSWORD" to "nope"))
        assertEquals(1, r.code, r.out)
        assertTrue("로그인" in r.out && "401" in r.out, r.out)
    }

    @Test
    fun `secret headers come from SMOKE_HEADERS_FILE and reach every call without showing in the output`() {
        val file = java.nio.file.Files.createTempFile("smoke-headers", ".txt").also { it.toFile().deleteOnExit(); java.nio.file.Files.writeString(it, "\nX-Revision-Tag: $headerValue\n") }
        val env = mapOf("SMOKE_EMAIL" to "smoke@example.com", "SMOKE_PASSWORD" to password, "SMOKE_HEADERS_FILE" to file.toString())
        val r = deploy(url, "--wait", "0", env = env)
        assertEquals(0, r.code, r.out)
        assertEquals(4, seenHeaders.size, seenHeaders.toString())
        assertFalse(headerValue in r.out, r.out)
    }

    @Test
    fun `an Authorization header or a missing headers file is a usage error`() {
        assertEquals(2, deploy(url, "-H", "Authorization: Bearer x", "--wait", "0").code)
        val env = mapOf("SMOKE_EMAIL" to "smoke@example.com", "SMOKE_PASSWORD" to password, "SMOKE_HEADERS_FILE" to "/nonexistent/headers")
        assertEquals(2, deploy(url, "--wait", "0", env = env).code)
    }

    @Test
    fun `missing credentials or url is a usage error (2), not a deployment failure`() {
        assertEquals(2, deploy(url, env = emptyMap()).code)
        assertEquals(2, deploy("--wait", "0").code)
    }

    @Test
    fun `an unreachable address fails`() {
        val r = deploy("http://127.0.0.1:1", "--wait", "0")
        assertEquals(1, r.code, r.out)
    }
}
