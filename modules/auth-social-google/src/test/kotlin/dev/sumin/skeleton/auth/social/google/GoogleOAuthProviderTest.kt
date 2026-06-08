package dev.sumin.skeleton.auth.social.google

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.sumin.skeleton.auth.social.config.AuthSocialProperties
import dev.sumin.skeleton.auth.social.oauth.OAuthInvalidAuthorizationCodeException
import dev.sumin.skeleton.common.http.DefaultExternalHttpClient
import dev.sumin.skeleton.common.http.DefaultExternalHttpErrorMapper
import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.http.OutboundHttpProperties
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.springframework.web.reactive.function.client.WebClient

class GoogleOAuthProviderTest {
    private lateinit var server: HttpServer
    private lateinit var httpClient: ExternalHttpClient
    private lateinit var provider: GoogleOAuthProvider
    private val requests = Collections.synchronizedList(mutableListOf<RecordedRequest>())

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
        httpClient = DefaultExternalHttpClient(
            webClientBuilder = WebClient.builder(),
            properties = OutboundHttpProperties(defaultResponseTimeout = Duration.ofSeconds(2)),
            defaultErrorMapper = DefaultExternalHttpErrorMapper(),
            customizers = emptyList(),
        )
        provider = GoogleOAuthProvider(
            httpClient = httpClient,
            properties = AuthSocialProperties.Provider(
                enabled = true,
                clientId = "google-client",
                clientSecret = "google-secret",
                redirectUri = "https://app.example.com/oauth/google/callback",
                tokenBaseUrl = "http://localhost:${server.address.port}",
                profileBaseUrl = "http://localhost:${server.address.port}",
                tokenPath = "/token",
                profilePath = "/userinfo",
            ),
        )
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `fetch profile exchanges authorization code and maps google userinfo`() {
        val profile = provider.fetchProfile("google-code", redirectUri = null)

        assertEquals("google", profile.provider)
        assertEquals("google-sub-1", profile.providerUserId)
        assertEquals("google@example.com", profile.email)
        assertEquals("google@example.com", profile.username)
        assertEquals("Google User", profile.displayName)

        val tokenRequest = requests.first()
        assertEquals("POST", tokenRequest.method)
        assertEquals("/token", tokenRequest.path)
        assertEquals(
            mapOf(
                "grant_type" to "authorization_code",
                "client_id" to "google-client",
                "client_secret" to "google-secret",
                "code" to "google-code",
                "redirect_uri" to "https://app.example.com/oauth/google/callback",
            ),
            tokenRequest.formBody(),
        )

        val profileRequest = requests.last()
        assertEquals("GET", profileRequest.method)
        assertEquals("/userinfo", profileRequest.path)
        assertEquals("Bearer google-access-token", profileRequest.headers["authorization"]?.single())
    }

    @Test
    fun `token error maps to invalid authorization code`() {
        assertFailsWith<OAuthInvalidAuthorizationCodeException> {
            provider.fetchProfile("invalid-code", redirectUri = null)
        }
    }

    private fun handle(exchange: HttpExchange) {
        val body = exchange.requestBody.bufferedReader().use { it.readText() }
        requests += RecordedRequest(
            method = exchange.requestMethod,
            path = exchange.requestURI.path,
            headers = exchange.requestHeaders.mapKeys { it.key.lowercase() },
            body = body,
        )

        when (exchange.requestURI.path) {
            "/token" -> {
                if (body.contains("code=invalid-code")) {
                    exchange.respond(400, """{"error":"invalid_grant"}""")
                } else {
                    exchange.respond(200, """{"access_token":"google-access-token","token_type":"Bearer","expires_in":3600}""")
                }
            }
            "/userinfo" -> exchange.respond(
                200,
                """{"sub":"google-sub-1","email":"google@example.com","name":"Google User","picture":"https://example.com/google.png"}""",
            )
            else -> exchange.respond(404, """{"message":"not found"}""")
        }
    }

    private fun HttpExchange.respond(status: Int, body: String) {
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(status, body.toByteArray().size.toLong())
        responseBody.use { output -> output.write(body.toByteArray()) }
    }

    private fun RecordedRequest.formBody(): Map<String, String> =
        body.split("&")
            .filter { it.isNotBlank() }
            .associate { pair ->
                val parts = pair.split("=", limit = 2)
                URLDecoder.decode(parts[0], StandardCharsets.UTF_8) to
                    URLDecoder.decode(parts.getOrElse(1) { "" }, StandardCharsets.UTF_8)
            }

    data class RecordedRequest(
        val method: String,
        val path: String,
        val headers: Map<String, List<String>>,
        val body: String,
    )
}
