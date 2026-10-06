package dev.sumin.skeleton.auth.social.x

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.sumin.skeleton.auth.social.oauth.NonceMode
import dev.sumin.skeleton.auth.social.oauth.OAuthCodeExchange
import dev.sumin.skeleton.auth.social.oauth.OAuthInvalidAuthorizationCodeException
import dev.sumin.skeleton.auth.social.oauth.OAuthPkceException
import dev.sumin.skeleton.auth.social.oauth.PkceMode
import dev.sumin.skeleton.common.http.DefaultExternalHttpClient
import dev.sumin.skeleton.common.http.DefaultExternalHttpErrorMapper
import dev.sumin.skeleton.common.http.OutboundHttpProperties
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.web.reactive.function.client.WebClient

/** 응답 모양은 X API v2 문서에서: docs.x.com/resources/fundamentals/authentication/oauth-2-0/authorization-code · docs.x.com/x-api/users/get-my-user */
class XOAuthProviderTest {
    private data class Req(val method: String, val path: String, val query: String?, val headers: Map<String, List<String>>, val body: String)

    private lateinit var server: HttpServer
    private val requests = Collections.synchronizedList(mutableListOf<Req>())
    private val challenges = ConcurrentHashMap<String, String>()   // code -> S256 challenge
    private val used = ConcurrentHashMap.newKeySet<String>()
    private var meStatus = 200
    private var meBody = """{"data":{"id":"2244994945","name":"X Dev","username":"TwitterDev","profile_image_url":"https://pbs.twimg.com/profile_images/1/x_normal.jpg"}}"""
    private var meHeaders = mapOf<String, String>()
    private var tokenStatus: Pair<Int, String>? = null
    private val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"

    private fun s256(v: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(v.toByteArray()))

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        server.createContext("/") { ex -> handle(ex) }
        server.start()
    }

    @AfterTest fun stop() = server.stop(0)

    private fun provider(requestEmail: Boolean = false, trustConfirmedEmail: Boolean = false, secret: String = "x-secret") = XOAuthProvider(
        httpClient = DefaultExternalHttpClient(WebClient.builder(), OutboundHttpProperties(defaultResponseTimeout = Duration.ofSeconds(3)), DefaultExternalHttpErrorMapper(), emptyList()),
        properties = XProperties(clientId = "x-client", clientSecret = secret, redirectUri = "https://app.example.com/cb/x", requestEmail = requestEmail, trustConfirmedEmail = trustConfirmedEmail, apiBaseUrl = "http://localhost:${server.address.port}"),
    )

    private fun issue(code: String, verifierToChallenge: String? = verifier) { verifierToChallenge?.let { challenges[code] = s256(it) } }
    private fun exchange(code: String, v: String? = verifier) = OAuthCodeExchange(code, "https://app.example.com/cb/x", v, null)

    private fun handle(ex: HttpExchange) {
        val body = ex.requestBody.bufferedReader().use { it.readText() }
        requests += Req(ex.requestMethod, ex.requestURI.path, ex.requestURI.rawQuery, ex.requestHeaders.mapKeys { it.key.lowercase() }, body)
        when (ex.requestURI.path) {
            "/2/oauth2/token" -> {
                tokenStatus?.let { (s, b) -> return ex.respond(s, b) }
                val basic = ex.requestHeaders.getFirst("Authorization")?.removePrefix("Basic ")?.let { String(Base64.getDecoder().decode(it)) }
                if (basic != "x-client:x-secret") return ex.respond(401, """{"error":"unauthorized_client","error_description":"Missing valid authorization header or client_id"}""")
                val form = body.split("&").associate { val p = it.split("=", limit = 2); URLDecoder.decode(p[0], StandardCharsets.UTF_8) to URLDecoder.decode(p[1], StandardCharsets.UTF_8) }
                val code = form["code"]
                if (code == null || !challenges.containsKey(code) || !used.add(code)) return ex.respond(400, """{"error":"invalid_request","error_description":"Value passed for the authorization code was invalid."}""")
                if (form["code_verifier"]?.let { s256(it) } != challenges[code]) return ex.respond(400, """{"error":"invalid_request","error_description":"Value passed for the code verifier was invalid."}""")
                ex.respond(200, """{"token_type":"bearer","expires_in":7200,"access_token":"x-access-token","scope":"users.read tweet.read"}""")
            }
            "/2/users/me" -> {
                meHeaders.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
                ex.respond(meStatus, meBody)
            }
            else -> ex.respond(404, "{}")
        }
    }

    private fun HttpExchange.respond(status: Int, body: String) {
        responseHeaders.add("Content-Type", "application/json")
        val b = body.toByteArray()
        sendResponseHeaders(status, b.size.toLong())
        responseBody.use { it.write(b) }
    }

    @Test
    fun `confidential client - Basic auth on the token endpoint, PKCE verifier forwarded, profile from users me`() {
        issue("c1")
        val profile = provider().fetchProfile(exchange("c1"))
        assertEquals("x", profile.provider)
        assertEquals("2244994945", profile.providerUserId)
        assertEquals("TwitterDev", profile.username)
        assertEquals("X Dev", profile.displayName)
        assertEquals("https://pbs.twimg.com/profile_images/1/x_normal.jpg", profile.avatarUrl)
        assertNull(profile.email)
        assertFalse(profile.emailVerified)

        val token = requests.first { it.path == "/2/oauth2/token" }
        assertEquals("POST", token.method)
        assertTrue(token.headers.getValue("authorization").single().startsWith("Basic "))
        assertFalse("client_secret" in token.body, "the secret travels only in the Basic header")
        val form = token.body.split("&").associate { val p = it.split("=", limit = 2); p[0] to URLDecoder.decode(p[1], StandardCharsets.UTF_8) }
        assertEquals(mapOf("grant_type" to "authorization_code", "code" to "c1", "redirect_uri" to "https://app.example.com/cb/x", "code_verifier" to verifier), form)

        val me = requests.first { it.path == "/2/users/me" }
        assertEquals("Bearer x-access-token", me.headers.getValue("authorization").single())
        assertEquals("user.fields=profile_image_url", me.query)
    }

    @Test
    fun `X declares PKCE required, no nonce, and tells the frontend its authorize endpoint and scopes`() {
        val p = provider()
        assertEquals(PkceMode.REQUIRED, p.pkce)
        assertEquals(NonceMode.UNSUPPORTED, p.nonce)
        assertEquals("https://x.com/i/oauth2/authorize", p.authorize?.url)
        assertEquals(listOf("users.read", "tweet.read"), p.authorize?.scopes)
        assertEquals(mapOf("response_type" to "code"), p.authorize?.params)
        assertEquals("x-client", p.publicClientId)
        assertTrue(p.autoEnabled)
        assertEquals(listOf("users.read", "tweet.read", "users.email"), provider(requestEmail = true).authorize?.scopes)
    }

    @Test
    fun `with requestEmail the confirmed_email field is asked for - and never called verified unless the project says X vouches for it`() {
        meBody = """{"data":{"id":"1","name":"N","username":"u","confirmed_email":"me@example.com"}}"""
        issue("c2"); issue("c3")
        val unverified = provider(requestEmail = true).fetchProfile(exchange("c2"))
        assertEquals("me@example.com", unverified.email)
        assertFalse(unverified.emailVerified)
        assertEquals("user.fields=profile_image_url,confirmed_email", requests.first { it.path == "/2/users/me" }.query)
        assertTrue(provider(requestEmail = true, trustConfirmedEmail = true).fetchProfile(exchange("c3")).emailVerified)
    }

    @Test
    fun `an app without the email permission simply gets no email - the account is address-less`() {
        issue("c4")
        val profile = provider(requestEmail = true).fetchProfile(exchange("c4"))
        assertNull(profile.email)
    }

    @Test
    fun `without requestEmail the email is never asked for even if X would send it`() {
        meBody = """{"data":{"id":"1","name":"N","username":"u","confirmed_email":"me@example.com"}}"""
        issue("c5")
        assertNull(provider().fetchProfile(exchange("c5")).email)
    }

    @Test
    fun `an authorization code is single use`() {
        issue("c6")
        val p = provider()
        p.fetchProfile(exchange("c6"))
        assertFailsWith<OAuthInvalidAuthorizationCodeException> { p.fetchProfile(exchange("c6")) }
    }

    @Test
    fun `a wrong verifier is the PKCE error and an unknown code is a refused code`() {
        issue("c7")
        assertFailsWith<OAuthPkceException> { provider().fetchProfile(exchange("c7", "y".repeat(43))) }
        assertFailsWith<OAuthInvalidAuthorizationCodeException> { provider().fetchProfile(exchange("never")) }
    }

    @Test
    fun `wrong client credentials are a gateway problem not the user's code`() {
        issue("c8")
        assertFailsWith<IllegalStateException> { provider(secret = "wrong").fetchProfile(exchange("c8")) }
    }

    @Test
    fun `a 429 from users me carries the reset time of the rate limit window`() {
        issue("c9")
        meStatus = 429
        meBody = """{"title":"Too Many Requests","detail":"Too Many Requests","type":"about:blank","status":429}"""
        meHeaders = mapOf("x-rate-limit-reset" to "1700000000", "x-rate-limit-remaining" to "0")
        val ex = assertFailsWith<XRateLimitedException> { provider().fetchProfile(exchange("c9")) }
        assertEquals(1_700_000_000L, ex.resetEpochSeconds)
        assertTrue("rate limit" in ex.message!!.lowercase(), ex.message)
    }

    @Test
    fun `a 403 from users me names X's own reason - an API tier or app permission problem - in the message`() {
        issue("c10")
        meStatus = 403
        meBody = """{"client_id":"123","detail":"When authenticating requests to the Twitter API v2 endpoints, you must use keys and tokens from a Twitter developer App that is attached to a Project.","registration_url":"https://developer.twitter.com/en/docs/projects/overview","title":"Client Forbidden","required_enrollment":"Standard Basic","reason":"client-not-enrolled","type":"https://api.twitter.com/2/problems/client-forbidden"}"""
        val ex = assertFailsWith<IllegalStateException> { provider().fetchProfile(exchange("c10")) }
        assertTrue("403" in ex.message!! && "Client Forbidden" in ex.message!!, ex.message)
    }

    @Test
    fun `a users me answer without data is an error not an empty profile`() {
        issue("c11")
        meBody = """{"errors":[{"message":"nothing"}]}"""
        assertFailsWith<IllegalStateException> { provider().fetchProfile(exchange("c11")) }
    }

    @Test
    fun `a blank client secret refuses to start - X apps are confidential clients here`() {
        assertFailsWith<IllegalArgumentException> { provider(secret = "") }
    }
}
