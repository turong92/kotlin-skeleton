package dev.sumin.skeleton.payment.googleplay

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpServer
import dev.sumin.skeleton.common.http.DefaultExternalHttpClient
import dev.sumin.skeleton.common.http.DefaultExternalHttpErrorMapper
import dev.sumin.skeleton.common.http.OutboundHttpProperties
import dev.sumin.skeleton.common.time.TimeProvider
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Collections
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import org.springframework.web.reactive.function.client.WebClient
import tools.jackson.databind.json.JsonMapper

internal val NOW: Instant = Instant.parse("2026-11-04T10:00:00Z")
internal const val PACKAGE = "com.example.sample"
internal const val AUDIENCE = "https://api.example.com/api/v1/payments/webhooks/googleplay"
internal const val PUSH_EMAIL = "pubsub-push@sample-project.iam.gserviceaccount.com"
internal const val SA_EMAIL = "play-api@sample-project.iam.gserviceaccount.com"
internal const val ACCOUNT_TOKEN = "6f1c2a3e-0000-4000-8000-000000000001"

private val JSON = JsonMapper.builder().build()

private fun rsaKeys(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

/** 시험용 서비스 계정 키 - 서버가 만든 JWT assertion 을 가짜 토큰 주소가 이 공개키로 확인한다. */
internal class TestServiceAccount {
    private val pair = rsaKeys()
    val publicKey get() = pair.public as RSAPublicKey
    val base64: String = run {
        val pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.private.encoded) + "\n-----END PRIVATE KEY-----\n"
        val json = JSON.writeValueAsString(linkedMapOf("type" to "service_account", "project_id" to "sample-project", "client_email" to SA_EMAIL, "private_key" to pem, "token_uri" to "https://evil.example.com/token"))
        Base64.getEncoder().encodeToString(json.toByteArray())
    }
}

/** 시험용 구글 OIDC 서명 키 - 가짜 서버의 공개키 주소가 이 공개키를 내놓고, 시험이 push 토큰을 이 키로 서명한다. */
internal class TestOidc(val kid: String = "test-key-1") {
    private val pair = rsaKeys()
    val jwks: String = JWKSet(RSAKey.Builder(pair.public as RSAPublicKey).keyID(kid).build()).toString()

    fun token(
        audience: String = AUDIENCE,
        email: String? = PUSH_EMAIL,
        emailVerified: Boolean? = true,
        issuer: String = "https://accounts.google.com",
        expires: Instant = NOW.plus(Duration.ofMinutes(30)),
        kid: String? = this.kid,
        signer: TestOidc = this,
    ): String {
        val claims = JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject("1234567890").issueTime(Date.from(NOW)).expirationTime(Date.from(expires)).apply {
            if (email != null) claim("email", email)
            if (emailVerified != null) claim("email_verified", emailVerified)
        }.build()
        val header = JWSHeader.Builder(JWSAlgorithm.RS256).apply { if (kid != null) keyID(kid) }.build()
        return SignedJWT(header, claims).apply { sign(RSASSASigner(signer.pair.private as RSAPrivateKey)) }.serialize()
    }
}

internal fun properties(server: FakePlayServer, account: TestServiceAccount = TestServiceAccount()) = GooglePlayProperties(
    enabled = true,
    packageName = PACKAGE,
    serviceAccount = account.base64,
    pubsub = GooglePlayProperties.Pubsub(audience = AUDIENCE, serviceAccountEmail = PUSH_EMAIL),
    baseUrl = server.baseUrl,
    tokenUrl = "${server.baseUrl}/token",
    certsUrl = "${server.baseUrl}/certs",
)

internal fun httpClient() = DefaultExternalHttpClient(
    webClientBuilder = WebClient.builder(),
    properties = OutboundHttpProperties(defaultConnectTimeout = Duration.ofSeconds(30), defaultResponseTimeout = Duration.ofSeconds(30)),
    defaultErrorMapper = DefaultExternalHttpErrorMapper(),
    customizers = emptyList(),
)

internal fun provider(server: FakePlayServer, account: TestServiceAccount = TestServiceAccount()) =
    GooglePlayPaymentProvider(httpClient(), properties(server, account), TimeProvider { NOW })

// --- 구글 응답 모양 ---

internal fun money(currency: String, units: Long, nanos: Int = 0) = """{"currencyCode":"$currency","units":"$units","nanos":$nanos}"""

internal fun orderJson(orderId: String, currency: String = "KRW", units: Long = 3000, nanos: Int = 0, state: String = "PROCESSED", createTime: Instant = NOW.minusSeconds(3600)) =
    """{"orderId":"$orderId","state":"$state","createTime":"$createTime","total":${money(currency, units, nanos)}}"""

internal fun productJson(
    productId: String = "cheer3000",
    orderId: String? = "GPA.3300-0000-0000-00001",
    state: String = "PURCHASED",
    quantity: Int? = 1,
    account: String? = ACCOUNT_TOKEN,
    test: Boolean = false,
    acknowledged: Boolean = false,
): String = buildString {
    append("""{"kind":"androidpublisher#productPurchaseV2","productLineItem":[{"productId":"$productId","productOfferDetails":{""")
    if (quantity != null) append(""""quantity":$quantity""")
    append("}}],")
    append(""""purchaseStateContext":{"purchaseState":"$state"},""")
    append(""""acknowledgementState":"${if (acknowledged) "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED" else "ACKNOWLEDGEMENT_STATE_PENDING"}",""")
    if (orderId != null) append(""""orderId":"$orderId",""")
    if (account != null) append(""""obfuscatedExternalAccountId":"$account",""")
    if (test) append(""""testPurchaseContext":{"fopType":"TEST"},""")
    append(""""purchaseCompletionTime":"${NOW.minusSeconds(3600)}"}""")
}

internal fun subscriptionJson(
    productId: String = "premium",
    basePlanId: String = "monthly",
    state: String = "SUBSCRIPTION_STATE_ACTIVE",
    latestOrderId: String? = "GPA.3300-0000-0000-00010",
    expiry: Instant = NOW.plus(Duration.ofDays(30)),
    account: String? = ACCOUNT_TOKEN,
    linked: String? = null,
    test: Boolean = false,
    acknowledged: Boolean = false,
): String = buildString {
    append("""{"kind":"androidpublisher#subscriptionPurchaseV2","startTime":"${NOW.minus(Duration.ofDays(1))}","subscriptionState":"$state",""")
    append(""""acknowledgementState":"${if (acknowledged) "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED" else "ACKNOWLEDGEMENT_STATE_PENDING"}",""")
    append(""""lineItems":[{"productId":"$productId","expiryTime":"$expiry","offerDetails":{"basePlanId":"$basePlanId"}""")
    if (latestOrderId != null) append(""","latestSuccessfulOrderId":"$latestOrderId"""")
    append("}]")
    if (linked != null) append(""","linkedPurchaseToken":"$linked"""")
    if (account != null) append(""","externalAccountIdentifiers":{"obfuscatedExternalAccountId":"$account"}""")
    if (test) append(""","testPurchase":{}""")
    append("}")
}

/** Pub/Sub push 본문 - `message.data` 는 RTDN(DeveloperNotification) JSON 의 base64 이다. */
internal fun pushBody(notification: String, messageId: String = "7000000000001"): String {
    val data = Base64.getEncoder().encodeToString(notification.toByteArray())
    return """{"message":{"data":"$data","messageId":"$messageId","publishTime":"$NOW"},"subscription":"projects/sample-project/subscriptions/play-rtdn"}"""
}

internal fun subscriptionNotification(type: Int, token: String, packageName: String = PACKAGE, subscriptionId: String = "premium") =
    """{"version":"1.0","packageName":"$packageName","eventTimeMillis":"${NOW.toEpochMilli()}","subscriptionNotification":{"version":"1.0","notificationType":$type,"purchaseToken":"$token","subscriptionId":"$subscriptionId"}}"""

internal fun voidedNotification(token: String, orderId: String, productType: Int = 2, refundType: Int = 1, packageName: String = PACKAGE) =
    """{"version":"1.0","packageName":"$packageName","eventTimeMillis":"${NOW.toEpochMilli()}","voidedPurchaseNotification":{"purchaseToken":"$token","orderId":"$orderId","productType":$productType,"refundType":$refundType}}"""

/**
 * 가짜 Google 서버 - 루프백에만 묶는다. 토큰 주소(`/token`, 서비스 계정 assertion 을 시험용 공개키로 확인) · androidpublisher(productsv2 · subscriptionsv2 · orders · acknowledge) ·
 * OIDC 공개키(`/certs`)를 흉내 낸다. 받은 요청을 남긴다. 모르는 토큰 · 주문은 404.
 */
internal class FakePlayServer(private val account: TestServiceAccount? = null, private val oidc: TestOidc? = null) : AutoCloseable {
    data class Req(val method: String, val path: String, val auth: String?, val body: String)

    val requests: MutableList<Req> = Collections.synchronizedList(mutableListOf())
    val tokenClaims: MutableList<Map<String, Any?>> = Collections.synchronizedList(mutableListOf())
    var certFetches = 0

    private val products = ConcurrentHashMap<String, String>()
    private val subscriptions = ConcurrentHashMap<String, String>()
    private val orders = ConcurrentHashMap<String, String>()
    private val forced = ConcurrentHashMap<String, ConcurrentLinkedQueue<Int>>()
    private val base = "/androidpublisher/v3/applications/$PACKAGE"

    fun product(token: String, json: String) {
        products[token] = json
    }

    fun subscription(token: String, json: String) {
        subscriptions[token] = json
    }

    fun order(orderId: String, json: String) {
        orders[orderId] = json
    }

    /** 이 경로 조각에 맞는 다음 요청 하나를 이 상태로 답한다(503 · 400 …). */
    fun failNext(pathPart: String, status: Int) {
        forced.computeIfAbsent(pathPart) { ConcurrentLinkedQueue() }.add(status)
    }

    fun pathsCalled(): List<String> = requests.map { it.path }

    /** 키가 `/` 로 끝나면 경로에 들어 있는 것, 아니면 경로가 그것으로 끝나는 것(`/token` 은 토큰 주소만, `…/tokens/x` 는 아니다). */
    private fun forcedFor(path: String): Int? =
        forced.entries.firstOrNull { if (it.key.endsWith("/")) path.contains(it.key) else path.endsWith(it.key) }?.value?.poll()

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val path = ex.requestURI.path
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            requests += Req(ex.requestMethod, path, ex.requestHeaders.getFirst("Authorization"), body)
            val force = forcedFor(path)
            val (status, out) = when {
                force != null -> force to """{"error":{"code":$force,"message":"forced","status":"UNAVAILABLE"}}"""
                path == "/token" -> token(body)
                path == "/certs" -> {
                    certFetches++
                    (oidc?.let { 200 to it.jwks }) ?: (404 to "{}")
                }
                path.startsWith("$base/purchases/productsv2/tokens/") -> lookup(products, path.substringAfterLast('/'))
                path.startsWith("$base/purchases/subscriptionsv2/tokens/") -> lookup(subscriptions, path.substringAfterLast('/'))
                path.startsWith("$base/orders/") -> lookup(orders, path.substringAfterLast('/'))
                path.endsWith(":acknowledge") && ex.requestMethod == "POST" -> 204 to ""
                else -> 404 to "{}"
            }
            val bytes = out.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.responseBody.close()
        }
        start()
    }

    private fun lookup(map: Map<String, String>, key: String): Pair<Int, String> =
        map[key]?.let { 200 to it } ?: (404 to """{"error":{"code":404,"message":"not found","status":"NOT_FOUND"}}""")

    private fun token(form: String): Pair<Int, String> {
        val fields = form.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
        val jwt = SignedJWT.parse(fields["assertion"] ?: return 400 to "{}")
        val verified = account?.let { jwt.verify(com.nimbusds.jose.crypto.RSASSAVerifier(it.publicKey)) } ?: false
        if (fields["grant_type"] != "urn:ietf:params:oauth:grant-type:jwt-bearer" || !verified) return 400 to """{"error":"invalid_grant"}"""
        tokenClaims += jwt.jwtClaimsSet.claims + mapOf("alg" to jwt.header.algorithm.name)
        return 200 to """{"access_token":"ya29.fake","expires_in":3600,"token_type":"Bearer"}"""
    }

    val baseUrl get() = "http://127.0.0.1:${server.address.port}"

    override fun close() = server.stop(0)
}
