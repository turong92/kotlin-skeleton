package dev.sumin.skeleton.notification.fcm

import java.io.IOException
import java.net.URI
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

private val json = JsonMapper.builder().build()

fun jsonOf(text: String): JsonNode = json.readTree(text)

/** 시험 안에서 만든 서비스 계정 — RSA 키를 매번 새로 만든다(실제 키를 레포에 넣지 않는다) */
class TestServiceAccount(
    val projectId: String = "demo-project-1",
    val clientEmail: String = "push@demo-project-1.iam.gserviceaccount.com",
    /** 일부러 엉뚱한 `token_uri` 를 넣어 두고, 센더가 이 주소로 나가지 않는지 본다 */
    private val tokenUri: String = "https://evil.example.com/steal",
) {
    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val publicKey: RSAPublicKey = pair.public as RSAPublicKey
    val privatePem: String = "-----BEGIN PRIVATE KEY-----\n" +
        Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.private.encoded) + "\n-----END PRIVATE KEY-----\n"

    fun json(projectId: String = this.projectId): String = json.writeValueAsString(
        linkedMapOf(
            "type" to "service_account", "project_id" to projectId, "private_key_id" to "abc123",
            "private_key" to privatePem, "client_email" to clientEmail, "client_id" to "1", "token_uri" to tokenUri,
        ),
    )

    fun base64(): String = Base64.getEncoder().encodeToString(json().toByteArray())

    /** 서비스 계정이 서명한 JWT 인지 — 서명 · iss · scope · aud · 수명을 확인하고 클레임을 돌려준다 */
    fun verifyAssertion(assertion: String): JsonNode {
        val (h, c, s) = assertion.split('.')
        val verifier = Signature.getInstance("SHA256withRSA").apply { initVerify(publicKey); update("$h.$c".toByteArray()) }
        check(verifier.verify(Base64.getUrlDecoder().decode(s))) { "bad assertion signature" }
        val header = jsonOf(String(Base64.getUrlDecoder().decode(h)))
        check(header["alg"].asString() == "RS256")
        return jsonOf(String(Base64.getUrlDecoder().decode(c)))
    }
}

/** 시험 안 가짜 구글 — 토큰 발급과 메시지 보내기를 주소로 가려 받고, 받은 요청을 모두 기록한다 */
class FakeGoogle(private val account: TestServiceAccount) : FcmHttp {
    class Received(val url: URI, val headers: Map<String, String>, val body: String)

    val requests = CopyOnWriteArrayList<Received>()
    val tokenRequests get() = requests.filter { it.url.host == "oauth2.googleapis.com" }
    val sends get() = requests.filter { it.url.host == "fcm.googleapis.com" }

    @Volatile var tokenStatus = 200
    @Volatile var tokenBody: String? = null
    @Volatile var tokenExpiresIn = 3600
    @Volatile var sendStatus = 200
    @Volatile var sendBody = """{"name":"projects/demo-project-1/messages/0:1"}"""
    @Volatile var sendRetryAfter: String? = null
    @Volatile var failWith: IOException? = null
    private var issued = 0

    override fun post(url: URI, headers: Map<String, String>, body: ByteArray, timeout: Duration): FcmHttpResponse {
        requests += Received(url, headers.mapKeys { it.key.lowercase() }, String(body))
        failWith?.let { throw it }
        return if (url.host == "oauth2.googleapis.com") {
            val form = String(body).split('&').associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
            check(form["grant_type"] == "urn:ietf:params:oauth:grant-type:jwt-bearer")
            account.verifyAssertion(form.getValue("assertion"))   // 서명이 틀리면 시험이 터진다
            FcmHttpResponse(tokenStatus, tokenBody ?: """{"access_token":"ya29.token-${++issued}","expires_in":$tokenExpiresIn,"token_type":"Bearer"}""", null)
        } else {
            FcmHttpResponse(sendStatus, sendBody, sendRetryAfter)
        }
    }
}

/** FCM v1 의 오류 모양 — `details` 에 FcmError(errorCode) 와 BadRequest(fieldViolations) */
fun fcmError(code: Int, status: String, errorCode: String? = null, violationField: String? = null, message: String = "the registration token secret-token-value is bad"): String {
    val details = buildList {
        if (violationField != null) add("""{"@type":"type.googleapis.com/google.rpc.BadRequest","fieldViolations":[{"field":"$violationField","description":"invalid"}]}""")
        if (errorCode != null) add("""{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError","errorCode":"$errorCode"}""")
    }.joinToString(",")
    return """{"error":{"code":$code,"message":"$message","status":"$status","details":[$details]}}"""
}
