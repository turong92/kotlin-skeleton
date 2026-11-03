package dev.sumin.skeleton.video.cloudrun

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.video.VideoWakeRuns
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

private val json = JsonMapper.builder().build()

fun jsonOf(text: String): JsonNode = json.readTree(text)

/** 시험 안에서 만든 서비스 계정 - RSA 키를 매번 새로 만든다(실제 키를 레포에 넣지 않는다) */
class TestServiceAccount(val clientEmail: String = "waker@demo-project.iam.gserviceaccount.com") {
    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val publicKey = pair.public as RSAPublicKey
    private val privatePem = "-----BEGIN PRIVATE KEY-----\n" +
        Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.private.encoded) + "\n-----END PRIVATE KEY-----\n"

    /** `token_uri` 는 일부러 엉뚱한 주소 - 키 파일이 호출 주소를 정하지 못하는지 본다 */
    fun json(): String = json.writeValueAsString(
        linkedMapOf("type" to "service_account", "private_key" to privatePem, "client_email" to clientEmail, "token_uri" to "https://evil.example.com/steal"),
    )

    /** 이 계정이 서명한 JWT 인지 - 서명을 확인하고 클레임을 돌려준다 */
    fun verifyAssertion(assertion: String): JsonNode {
        val (h, c, s) = assertion.split('.')
        val verifier = Signature.getInstance("SHA256withRSA").apply { initVerify(publicKey); update("$h.$c".toByteArray()) }
        check(verifier.verify(Base64.getUrlDecoder().decode(s))) { "bad assertion signature" }
        check(jsonOf(String(Base64.getUrlDecoder().decode(h)))["alg"].asString() == "RS256")
        return jsonOf(String(Base64.getUrlDecoder().decode(c)))
    }
}

/** 진짜 소켓 위의 가짜 구글 - 토큰 발급과 작업 실행을 받고, 받은 요청을 모두 기록한다 */
class FakeGoogleServer(private val account: TestServiceAccount) : AutoCloseable {
    class Received(val path: String, val authorization: String?, val body: String)

    val tokenRequests = CopyOnWriteArrayList<Received>()
    val runRequests = CopyOnWriteArrayList<Received>()
    @Volatile var runStatus = 200
    @Volatile var runBody = """{"name":"projects/p/locations/r/jobs/j/executions/e1"}"""
    @Volatile var tokenStatus = 200
    @Volatile var tokenExpiresIn = 3600
    private var issued = 0

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/token") { ex -> handle(ex, tokenRequests) { tokenReply(it) } }
        createContext("/v2/") { ex -> handle(ex, runRequests) { runStatus to runBody } }
        start()
    }

    val base: String get() = "http://127.0.0.1:${server.address.port}"

    private fun tokenReply(body: String): Pair<Int, String> {
        val form = body.split('&').associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
        check(form["grant_type"] == "urn:ietf:params:oauth:grant-type:jwt-bearer")
        account.verifyAssertion(form.getValue("assertion"))   // 서명이 틀리면 시험이 터진다
        return tokenStatus to """{"access_token":"ya29.token-${++issued}","expires_in":$tokenExpiresIn,"token_type":"Bearer"}"""
    }

    private fun handle(ex: HttpExchange, into: MutableList<Received>, reply: (String) -> Pair<Int, String>) {
        val body = String(ex.requestBody.readAllBytes())
        into += Received(ex.requestURI.path + (":" + ex.requestMethod), ex.requestHeaders.getFirst("Authorization"), body)
        val (status, text) = reply(body)
        val bytes = text.toByteArray()
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    override fun close() = server.stop(0)
}

/** 움직이는 시계 */
class TestClock(var now: Instant = Instant.parse("2026-11-04T00:00:00Z")) : TimeProvider {
    override fun now(): Instant = now
    fun advance(seconds: Long) { now = now.plusSeconds(seconds) }
}

/** 메모리 깨우기 기록 - 질문의 뜻은 JDBC 구현과 같다(그쪽은 video-jdbc 의 dbTest 가 증명) */
class InMemoryWakeRuns : VideoWakeRuns {
    private class Row(val at: Instant, val ok: Boolean)
    private val rows = CopyOnWriteArrayList<Row>()

    override fun record(at: Instant, ok: Boolean, execution: String?, error: String?) { rows += Row(at, ok) }
    override fun okSince(since: Instant): Int = rows.count { it.ok && !it.at.isBefore(since) }
    override fun lastOkAt(): Instant? = rows.filter { it.ok }.maxOfOrNull { it.at }
    override fun consecutiveFailures(): Int = rows.takeLastWhile { !it.ok }.size
    override fun deleteOlderThan(before: Instant): Int = rows.count { it.at.isBefore(before) }.also { rows.removeIf { r -> r.at.isBefore(before) } }
}
