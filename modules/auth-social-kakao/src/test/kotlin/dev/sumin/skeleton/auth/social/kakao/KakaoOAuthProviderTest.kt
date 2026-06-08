package dev.sumin.skeleton.auth.social.kakao

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.sumin.skeleton.auth.social.config.AuthSocialProperties
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
import org.springframework.web.reactive.function.client.WebClient

class KakaoOAuthProviderTest {
    private lateinit var server: HttpServer
    private lateinit var httpClient: ExternalHttpClient
    private lateinit var provider: KakaoOAuthProvider
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
        provider = KakaoOAuthProvider(
            httpClient = httpClient,
            properties = AuthSocialProperties.Provider(
                enabled = true,
                clientId = "kakao-client",
                clientSecret = "kakao-secret",
                redirectUri = "https://app.example.com/oauth/kakao/callback",
                tokenBaseUrl = "http://localhost:${server.address.port}",
                profileBaseUrl = "http://localhost:${server.address.port}",
                tokenPath = "/oauth/token",
                profilePath = "/v2/user/me",
            ),
        )
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `fetch profile exchanges authorization code and maps kakao account`() {
        val profile = provider.fetchProfile("kakao-code", redirectUri = "https://override.example.com/callback")

        assertEquals("kakao", profile.provider)
        assertEquals("12345", profile.providerUserId)
        assertEquals("kakao@example.com", profile.email)
        assertEquals("kakao@example.com", profile.username)
        assertEquals("Kakao User", profile.displayName)

        val tokenRequest = requests.first()
        assertEquals("POST", tokenRequest.method)
        assertEquals("/oauth/token", tokenRequest.path)
        assertEquals("https://override.example.com/callback", tokenRequest.formBody()["redirect_uri"])

        val profileRequest = requests.last()
        assertEquals("GET", profileRequest.method)
        assertEquals("/v2/user/me", profileRequest.path)
        assertEquals("Bearer kakao-access-token", profileRequest.headers["authorization"]?.single())
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
            "/oauth/token" -> exchange.respond(200, """{"access_token":"kakao-access-token","token_type":"Bearer"}""")
            "/v2/user/me" -> exchange.respond(
                200,
                """{"id":12345,"kakao_account":{"email":"kakao@example.com","profile":{"nickname":"Kakao User"}}}""",
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
