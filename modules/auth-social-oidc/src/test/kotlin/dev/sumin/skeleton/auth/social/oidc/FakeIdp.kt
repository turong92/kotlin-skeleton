package dev.sumin.skeleton.auth.social.oidc

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSSigner
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.sumin.skeleton.common.http.DefaultExternalHttpClient
import dev.sumin.skeleton.common.http.DefaultExternalHttpErrorMapper
import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.http.OutboundHttpProperties
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Collections
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import org.springframework.web.reactive.function.client.WebClient

data class Recorded(val method: String, val path: String, val headers: Map<String, List<String>>, val body: String) {
    fun form(): Map<String, String> = body.split("&").filter { it.isNotBlank() }.associate {
        val p = it.split("=", limit = 2)
        URLDecoder.decode(p[0], StandardCharsets.UTF_8) to URLDecoder.decode(p.getOrElse(1) { "" }, StandardCharsets.UTF_8)
    }
}

/**
 * 진짜 제공자 대신 서는 로컬 서버 (JDK HttpServer): discovery · authorize 는 없고 · token(PKCE S256 · 한 번 쓰는 코드 · 클라이언트 인증) · jwks · userinfo.
 * 응답의 모양(필드 이름 · 오류 본문)은 각 제공자의 공식 문서에서 가져왔다.
 */
class FakeIdp(val clientId: String = "client-1", val clientSecret: String = "secret-secret-secret-secret-0123456789") : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("localhost", 0), 0)
    val base = "http://localhost:${server.address.port}"
    val issuer = base
    val requests: MutableList<Recorded> = Collections.synchronizedList(mutableListOf())

    var rsaKeys: List<RSAKey> = listOf(RSAKeyGenerator(2048).keyID("k1").generate())
    val ecKey: ECKey = ECKeyGenerator(com.nimbusds.jose.jwk.Curve.P_256).keyID("ec1").generate()
    var servedJwks: () -> List<JWK> = { rsaKeys.map { it.toPublicJWK() } }
    var discoveryStatus = 200
    var discoveryIssuer: String = issuer
    var userinfoBody: (String) -> String = { """{"sub":"sub-1"}""" }
    var tokenFailure: Pair<Int, String>? = null
    var basicAuth = false

    class Issued(val challenge: String?, val redirectUri: String?, val idToken: String?, val accessToken: String, var used: Boolean = false)
    private val codes = ConcurrentHashMap<String, Issued>()

    fun issue(code: String, challenge: String?, redirectUri: String?, idToken: String?, accessToken: String = "at-$code") { codes[code] = Issued(challenge, redirectUri, idToken, accessToken) }

    fun count(path: String) = requests.count { it.path == path }

    init {
        server.createContext("/") { ex -> handle(ex) }
        server.start()
    }

    override fun close() = server.stop(0)

    fun claims(sub: String = "sub-1", nonce: String? = null, aud: Any = clientId, iss: String = issuer, exp: Instant = Instant.now().plusSeconds(600), extra: Map<String, Any> = emptyMap()): JWTClaimsSet =
        JWTClaimsSet.Builder().subject(sub).issuer(iss).audience(if (aud is List<*>) aud.map { it.toString() } else listOf(aud.toString()))
            .expirationTime(Date.from(exp)).issueTime(Date.from(Instant.now())).apply { nonce?.let { claim("nonce", it) }; extra.forEach { (k, v) -> claim(k, v) } }.build()

    fun sign(claims: JWTClaimsSet, key: RSAKey = rsaKeys.first()): String = signWith(claims, JWSAlgorithm.RS256, RSASSASigner(key), key.keyID)
    fun signHs256(claims: JWTClaimsSet, secret: String = clientSecret): String = signWith(claims, JWSAlgorithm.HS256, MACSigner(secret.toByteArray()), null)
    fun signEs256(claims: JWTClaimsSet): String = signWith(claims, JWSAlgorithm.ES256, ECDSASigner(ecKey), ecKey.keyID)
    private fun signWith(claims: JWTClaimsSet, alg: JWSAlgorithm, signer: JWSSigner, kid: String?): String {
        val header = JWSHeader.Builder(alg).apply { kid?.let { keyID(it) } }.build()
        return SignedJWT(header, claims).also { it.sign(signer) }.serialize()
    }

    fun s256(verifier: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))

    private fun handle(ex: HttpExchange) {
        val body = ex.requestBody.bufferedReader().use { it.readText() }
        val rec = Recorded(ex.requestMethod, ex.requestURI.path, ex.requestHeaders.mapKeys { it.key.lowercase() }, body)
        requests += rec
        when (ex.requestURI.path) {
            "/.well-known/openid-configuration" -> if (discoveryStatus != 200) ex.respond(discoveryStatus, """{"error":"boom"}""") else ex.respond(
                200,
                """{"issuer":"$discoveryIssuer","authorization_endpoint":"$base/authorize","token_endpoint":"$base/token","userinfo_endpoint":"$base/userinfo","jwks_uri":"$base/jwks","code_challenge_methods_supported":["S256"],"id_token_signing_alg_values_supported":["RS256"]}""",
            )
            "/jwks" -> ex.respond(200, JWKSet(servedJwks()).toString())
            "/userinfo" -> {
                val bearer = rec.headers["authorization"]?.firstOrNull()?.removePrefix("Bearer ")
                if (bearer == null) ex.respond(401, """{"error":"invalid_token"}""") else ex.respond(200, userinfoBody(bearer))
            }
            "/token" -> token(ex, rec)
            else -> ex.respond(404, """{"error":"not_found"}""")
        }
    }

    private fun token(ex: HttpExchange, rec: Recorded) {
        tokenFailure?.let { (status, body) -> return ex.respond(status, body) }
        val form = rec.form()
        val basic = rec.headers["authorization"]?.firstOrNull()?.takeIf { it.startsWith("Basic ") }?.removePrefix("Basic ")?.let { String(Base64.getDecoder().decode(it)) }
        val authOk = if (basicAuth) basic == "$clientId:$clientSecret" else form["client_id"] == clientId && form["client_secret"] == clientSecret
        if (!authOk) return ex.respond(401, """{"error":"invalid_client","error_description":"Client authentication failed"}""")
        if (form["grant_type"] != "authorization_code") return ex.respond(400, """{"error":"unsupported_grant_type"}""")
        val issued = codes[form["code"]]
        if (issued == null || issued.used) return ex.respond(400, """{"error":"invalid_grant","error_description":"authorization code is invalid, expired or already used"}""")
        issued.used = true   // 한 번 쓰는 코드: 틀린 검증기로 시도해도 코드는 탄다 (RFC 6749 §4.1.2)
        if (issued.redirectUri != null && form["redirect_uri"] != issued.redirectUri) return ex.respond(400, """{"error":"invalid_grant","error_description":"redirect_uri mismatch"}""")
        if (issued.challenge != null && (form["code_verifier"] == null || s256(form.getValue("code_verifier")) != issued.challenge)) {
            return ex.respond(400, """{"error":"invalid_grant","error_description":"code_verifier does not match the code_challenge"}""")
        }
        val id = issued.idToken?.let { ""","id_token":"$it"""" } ?: ""
        ex.respond(200, """{"access_token":"${issued.accessToken}","token_type":"Bearer","expires_in":2592000,"refresh_token":"rt","scope":"profile openid"$id}""")
    }

    private fun HttpExchange.respond(status: Int, body: String) {
        responseHeaders.add("Content-Type", "application/json")
        val bytes = body.toByteArray()
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    companion object {
        fun httpClient(): ExternalHttpClient = DefaultExternalHttpClient(
            webClientBuilder = WebClient.builder(),
            properties = OutboundHttpProperties(defaultResponseTimeout = Duration.ofSeconds(3)),
            defaultErrorMapper = DefaultExternalHttpErrorMapper(),
            customizers = emptyList(),
        )
    }
}
