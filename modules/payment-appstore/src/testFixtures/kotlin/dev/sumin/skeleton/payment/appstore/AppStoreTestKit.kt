package dev.sumin.skeleton.payment.appstore

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.util.Base64
import com.sun.net.httpserver.HttpServer
import dev.sumin.skeleton.common.http.DefaultExternalHttpClient
import dev.sumin.skeleton.common.http.DefaultExternalHttpErrorMapper
import dev.sumin.skeleton.common.http.OutboundHttpProperties
import dev.sumin.skeleton.common.time.TimeProvider
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.springframework.web.reactive.function.client.WebClient

val NOW: Instant = Instant.parse("2026-11-04T10:00:00Z")
const val BUNDLE = "com.example.sample"
const val APP_APPLE_ID = "1234567890"
const val ISSUER = "11111111-2222-3333-4444-555555555555"
const val KEY_ID = "ABC123DEFG"

const val INTERMEDIATE_OID = "1.2.840.113635.100.6.2.1"
const val LEAF_OID = "1.2.840.113635.100.6.11.1"

private fun ecKeys(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

/** 시험용 인증서 체인 - 루트 · 중간 · 잎. 애플 체인과 같은 모양(중간 · 잎에 애플 확장 OID, 비-중요 확장)이고, 옵션으로 OID 를 뺀다. */
class TestChain(
    val root: X509Certificate,
    val intermediate: X509Certificate,
    val leaf: X509Certificate,
    private val leafKey: ECPrivateKey,
) {
    fun x5c(vararg certs: X509Certificate = arrayOf(leaf, intermediate, root)): List<Base64> = certs.map { Base64.encode(it.encoded) }

    /** 잎 키로 서명한 JWS (alg ES256, x5c 세 장). */
    fun sign(payload: String, x5c: List<Base64> = x5c()): String {
        val header = JWSHeader.Builder(JWSAlgorithm.ES256).x509CertChain(x5c).build()
        return JWSObject(header, Payload(payload)).apply { sign(ECDSASigner(leafKey)) }.serialize()
    }

    companion object {
        fun create(intermediateOid: Boolean = true, leafOid: Boolean = true, notBefore: Instant = NOW.minus(Duration.ofDays(365)), notAfter: Instant = NOW.plus(Duration.ofDays(365))): TestChain {
            val rootKeys = ecKeys()
            val intermediateKeys = ecKeys()
            val leafKeys = ecKeys()
            val root = certificate("CN=Test Root", "CN=Test Root", rootKeys.public, rootKeys, ca = true, oid = null, notBefore, notAfter)
            val intermediate = certificate("CN=Test Root", "CN=Test Intermediate", intermediateKeys.public, rootKeys, ca = true, oid = INTERMEDIATE_OID.takeIf { intermediateOid }, notBefore, notAfter)
            val leaf = certificate("CN=Test Intermediate", "CN=Test Leaf", leafKeys.public, intermediateKeys, ca = false, oid = LEAF_OID.takeIf { leafOid }, notBefore, notAfter)
            return TestChain(root, intermediate, leaf, leafKeys.private as ECPrivateKey)
        }

        private fun certificate(issuer: String, subject: String, publicKey: java.security.PublicKey, signer: KeyPair, ca: Boolean, oid: String?, notBefore: Instant, notAfter: Instant): X509Certificate {
            val builder = JcaX509v3CertificateBuilder(X500Name(issuer), BigInteger.valueOf(System.nanoTime()), Date.from(notBefore), Date.from(notAfter), X500Name(subject), publicKey)
            builder.addExtension(Extension.basicConstraints, true, BasicConstraints(ca))
            if (oid != null) builder.addExtension(ASN1ObjectIdentifier(oid), false, DEROctetString(ByteArray(0)))
            return JcaX509CertificateConverter().getCertificate(builder.build(JcaContentSignerBuilder("SHA256withECDSA").build(signer.private)))
        }
    }
}

/** 앱 스토어 서버 API 인증용 .p8 키(PKCS#8 PEM) - 서버가 만든 ES256 JWT 를 시험이 이 공개키로 확인한다. */
class TestApiKey {
    private val pair = ecKeys()
    val publicKey get() = pair.public as java.security.interfaces.ECPublicKey
    val pem: String = "-----BEGIN PRIVATE KEY-----\n" + java.util.Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.private.encoded) + "\n-----END PRIVATE KEY-----"
}

fun properties(baseUrl: String = "", sandboxBaseUrl: String = "", key: TestApiKey = TestApiKey()) =
    AppStoreProperties(enabled = true, bundleId = BUNDLE, appAppleId = APP_APPLE_ID, issuerId = ISSUER, keyId = KEY_ID, privateKey = key.pem, baseUrl = baseUrl, sandboxBaseUrl = sandboxBaseUrl)

fun httpClient() = DefaultExternalHttpClient(
    webClientBuilder = WebClient.builder(),
    properties = OutboundHttpProperties(defaultConnectTimeout = Duration.ofSeconds(30), defaultResponseTimeout = Duration.ofSeconds(30)),
    defaultErrorMapper = DefaultExternalHttpErrorMapper(),
    customizers = emptyList(),
)

fun verifier(chain: TestChain, now: Instant = NOW) = AppStoreSignatureVerifier(setOf(chain.root), TimeProvider { now })

fun provider(chain: TestChain, prod: FakeAppStoreServer, sandbox: FakeAppStoreServer, key: TestApiKey = TestApiKey()) = AppStorePaymentProvider(
    httpClient = httpClient(),
    properties = properties(prod.baseUrl, sandbox.baseUrl, key),
    verifier = verifier(chain),
    time = TimeProvider { NOW },
)

/** 서명한 거래 정보(JWSTransactionDecodedPayload)의 JSON. 금액은 밀리 단위(`price`). */
fun transactionJson(
    transactionId: String = "2000000000000001",
    originalTransactionId: String = transactionId,
    productId: String = "com.example.sample.cheer3000",
    type: String = "Consumable",
    bundleId: String = BUNDLE,
    environment: String = "Production",
    price: Long? = 4_990_000,
    currency: String? = "KRW",
    appAccountToken: String? = "6f1c2a3e-0000-4000-8000-000000000001",
    purchaseDate: Instant = NOW.minusSeconds(3600),
    expiresDate: Instant? = null,
    revocationDate: Instant? = null,
    revocationType: String? = null,
    revocationPercentage: Int? = null,
    quantity: Int = 1,
    inAppOwnershipType: String = "PURCHASED",
): String = buildString {
    append("{\"transactionId\":\"$transactionId\",\"originalTransactionId\":\"$originalTransactionId\",\"bundleId\":\"$bundleId\",\"productId\":\"$productId\",\"type\":\"$type\",\"environment\":\"$environment\",")
    append("\"quantity\":$quantity,\"purchaseDate\":${purchaseDate.toEpochMilli()},\"signedDate\":${NOW.toEpochMilli()},\"inAppOwnershipType\":\"$inAppOwnershipType\"")
    if (price != null) append(",\"price\":$price")
    if (currency != null) append(",\"currency\":\"$currency\"")
    if (appAccountToken != null) append(",\"appAccountToken\":\"$appAccountToken\"")
    if (expiresDate != null) append(",\"expiresDate\":${expiresDate.toEpochMilli()}")
    if (revocationDate != null) append(",\"revocationDate\":${revocationDate.toEpochMilli()}")
    if (revocationType != null) append(",\"revocationType\":\"$revocationType\"")
    if (revocationPercentage != null) append(",\"revocationPercentage\":$revocationPercentage")
    append("}")
}

fun renewalJson(autoRenewStatus: Int = 1, gracePeriodExpiresDate: Instant? = null, isInBillingRetryPeriod: Boolean = false, autoRenewProductId: String = "com.example.sample.premium.monthly") = buildString {
    append("{\"originalTransactionId\":\"2000000000000010\",\"autoRenewStatus\":$autoRenewStatus,\"autoRenewProductId\":\"$autoRenewProductId\",\"isInBillingRetryPeriod\":$isInBillingRetryPeriod,\"environment\":\"Production\",\"signedDate\":${NOW.toEpochMilli()}")
    if (gracePeriodExpiresDate != null) append(",\"gracePeriodExpiresDate\":${gracePeriodExpiresDate.toEpochMilli()}")
    append("}")
}

/**
 * 가짜 앱 스토어 서버 API - 루프백에만 묶고, 시험용 체인으로 서명한 JWS 를 돌려준다. 받은 요청(경로 · 인증 헤더)을 남긴다.
 * `GET /inApps/v1/transactions/{id}` 와 `GET /inApps/v1/subscriptions/{id}` 를 흉내 내며, 모르는 거래는 404(`4040010`)다.
 */
class FakeAppStoreServer(private val chain: TestChain, private val environment: String = "Production") : AutoCloseable {
    data class Req(val method: String, val path: String, val auth: String?)

    val requests: MutableList<Req> = Collections.synchronizedList(mutableListOf())

    private val transactions = ConcurrentHashMap<String, String>()
    private val subscriptions = ConcurrentHashMap<String, String>()
    private val forced = ConcurrentHashMap<String, ConcurrentLinkedQueue<Int>>()

    /** 이 거래 id 를 아는 것으로 한다. [signer] 는 응답에 서명할 체인(기본은 서버의 체인; 시험이 다른 루트를 쓰게). */
    fun knows(transactionId: String, transactionJson: String, signer: TestChain = chain) {
        transactions[transactionId] = """{"signedTransactionInfo":"${signer.sign(transactionJson)}"}"""
    }

    /** 구독 상태 조회 답 - [status] 는 애플의 1 활성 · 2 만료 · 3 재시도 · 4 유예 · 5 취소, [latest] 는 최신 거래 JSON. */
    fun subscription(transactionId: String, status: Int, latest: String, renewal: String = renewalJson(), original: String = "2000000000000010", appAppleId: Long? = APP_APPLE_ID.toLong()) {
        subscriptions[transactionId] = buildString {
            append("{\"environment\":\"$environment\",\"bundleId\":\"$BUNDLE\"")
            if (appAppleId != null) append(",\"appAppleId\":$appAppleId")
            append(",\"data\":[{\"subscriptionGroupIdentifier\":\"21000000\",\"lastTransactions\":[{\"originalTransactionId\":\"$original\",\"status\":$status,")
            append("\"signedRenewalInfo\":\"${chain.sign(renewal)}\",\"signedTransactionInfo\":\"${chain.sign(latest)}\"}]}]}")
        }
    }

    /** 이 경로의 다음 요청 하나를 이 상태로 답한다(429 · 500 …). */
    fun failNext(path: String, status: Int) {
        forced.computeIfAbsent(path) { ConcurrentLinkedQueue() }.add(status)
    }

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val path = ex.requestURI.path
            requests += Req(ex.requestMethod, path, ex.requestHeaders.getFirst("Authorization"))
            val force = forced[path]?.poll()
            val id = path.substringAfterLast('/')
            val body = when {
                force != null -> null
                path.startsWith("/inApps/v1/transactions/") -> transactions[id]
                path.startsWith("/inApps/v1/subscriptions/") -> subscriptions[id]
                else -> null
            }
            val (status, out) = when {
                force != null -> force to """{"errorCode":5000000,"errorMessage":"x"}""".toByteArray()
                body != null -> 200 to body.toByteArray()
                else -> 404 to """{"errorCode":4040010,"errorMessage":"Transaction id not found."}""".toByteArray()
            }
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, out.size.toLong())
            ex.responseBody.use { it.write(out) }
        }
        start()
    }
    val baseUrl get() = "http://127.0.0.1:${server.address.port}"
    override fun close() = server.stop(0)
}

/** 알림 한 건의 본문 `{"signedPayload": …}` - 알림 본문(`ResponseBodyV2DecodedPayload`)과 안의 거래 · 갱신 정보를 시험 체인으로 서명한다. */
fun notificationBody(
    chain: TestChain,
    type: String,
    subtype: String? = null,
    uuid: String = "11111111-aaaa-4bbb-8ccc-000000000001",
    environment: String = "Production",
    bundleId: String = BUNDLE,
    appAppleId: String? = APP_APPLE_ID,
    transaction: String? = transactionJson(),
    renewal: String? = null,
    transactionSigner: TestChain = chain,
    payloadSigner: TestChain = chain,
): String {
    val data = buildString {
        append("{\"environment\":\"$environment\",\"bundleId\":\"$bundleId\",\"bundleVersion\":\"1\"")
        if (appAppleId != null && environment == "Production") append(",\"appAppleId\":$appAppleId")
        if (transaction != null) append(",\"signedTransactionInfo\":\"${transactionSigner.sign(transaction)}\"")
        if (renewal != null) append(",\"signedRenewalInfo\":\"${transactionSigner.sign(renewal)}\"")
        append("}")
    }
    val payload = buildString {
        append("{\"notificationType\":\"$type\",")
        if (subtype != null) append("\"subtype\":\"$subtype\",")
        append("\"notificationUUID\":\"$uuid\",\"data\":$data,\"version\":\"2.0\",\"signedDate\":${NOW.toEpochMilli()}}")
    }
    return """{"signedPayload":"${payloadSigner.sign(payload)}"}"""
}
