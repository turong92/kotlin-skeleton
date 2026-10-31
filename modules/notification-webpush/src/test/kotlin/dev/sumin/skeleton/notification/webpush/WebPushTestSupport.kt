package dev.sumin.skeleton.notification.webpush

import com.sun.net.httpserver.HttpServer
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** 시험 안에서 만든 키 — 실제 키를 레포에 넣지 않는다 */
class TestKeys {
    private val vapid = WebPushKeys.generate()
    val vapidPublic: String = WebPushKeys.encode(vapid.public)
    val vapidPrivate: String = WebPushKeys.encode(scalar(vapid.private))
}

/** 브라우저 쪽 키(구독) — p256dh · auth 를 내고, 받은 본문을 푼다 */
class TestSubscription {
    private val key = WebPushKeys.generate()
    val p256dh: String = WebPushKeys.encode(key.public)
    private val authBytes = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
    val auth: String = WebPushKeys.encode(authBytes)

    /** RFC 8291 수신 쪽 — aes128gcm 본문을 풀어 평문을 돌려준다 */
    fun decrypt(body: ByteArray): String {
        val salt = body.copyOfRange(0, 16)
        val idLen = body[20].toInt() and 0xff
        val asPublic = body.copyOfRange(21, 21 + idLen)
        val sealed = body.copyOfRange(21 + idLen, body.size)
        val ecdh = KeyAgreement.getInstance("ECDH").run {
            init(key.private)
            doPhase(WebPushKeys.publicKey(asPublic), true)
            generateSecret()
        }
        fun hmac(k: ByteArray, d: ByteArray) = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(k, "HmacSHA256")); doFinal(d) }
        val ikm = hmac(hmac(authBytes, ecdh), "WebPush: info\u0000".toByteArray() + key.public + asPublic + 1)
        val prk = hmac(salt, ikm)
        val cek = hmac(prk, "Content-Encoding: aes128gcm\u0000".toByteArray() + 1).copyOf(16)
        val nonce = hmac(prk, "Content-Encoding: nonce\u0000".toByteArray() + 1).copyOf(12)
        val plain = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce))
            doFinal(sealed)
        }
        check(plain.last() == 0x02.toByte()) { "missing last-record delimiter" }
        return String(plain, 0, plain.size - 1, Charsets.UTF_8)
    }
}

fun scalar(key: ECPrivateKey): ByteArray {
    val raw = BigInteger.ZERO.let { key.s.toByteArray() }.let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
    return ByteArray(32 - raw.size) + raw
}

/** 시험 안 가짜 푸시 서비스 — 받은 요청을 기록하고 정해 둔 상태로 답한다 */
class FakePushService(@Volatile var status: Int = 201, @Volatile var extraHeaders: Map<String, String> = emptyMap()) : AutoCloseable {
    class Received(val path: String, val headers: Map<String, String>, val body: ByteArray)

    val requests = CopyOnWriteArrayList<Received>()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { s ->
        s.createContext("/") { ex ->
            requests += Received(
                ex.requestURI.path,
                ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() },
                ex.requestBody.readAllBytes(),
            )
            extraHeaders.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
            ex.sendResponseHeaders(status, -1)
            ex.close()
        }
        s.start()
    }
    val origin: String get() = "http://127.0.0.1:${server.address.port}"
    val hits = AtomicInteger()
    override fun close() = server.stop(0)
}

private val json = JsonMapper.builder().build()

fun jsonOf(text: String): JsonNode = json.readTree(text)

/** VAPID JWT(ES256) 검증 — 서명이 공개 키로 맞고 (aud · exp · sub)가 기대대로인지. 클레임을 돌려준다 */
fun verifyVapid(authorization: String, publicKey: String): JsonNode {
    val match = Regex("^vapid t=([\\w-]+)\\.([\\w-]+)\\.([\\w-]+), k=([\\w-]+)$").matchEntire(authorization)
        ?: error("not a vapid Authorization header")
    val (h, c, s, k) = match.destructured
    check(k == publicKey) { "k must be the server public key" }
    val verifier = Signature.getInstance("SHA256withECDSAinP1363Format").apply {
        initVerify(WebPushKeys.publicKey(WebPushKeys.decode(publicKey)))
        update("$h.$c".toByteArray())
    }
    check(verifier.verify(WebPushKeys.decode(s))) { "bad JWT signature" }
    val header = jsonOf(String(WebPushKeys.decode(h)))
    check(header["alg"].asString() == "ES256" && header["typ"].asString() == "JWT")
    return jsonOf(String(WebPushKeys.decode(c)))
}
