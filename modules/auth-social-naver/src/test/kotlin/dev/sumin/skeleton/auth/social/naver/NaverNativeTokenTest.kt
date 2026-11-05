package dev.sumin.skeleton.auth.social.naver

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.sumin.skeleton.auth.social.config.AuthSocialProperties
import dev.sumin.skeleton.auth.social.oauth.NativeTokenKind
import dev.sumin.skeleton.auth.social.oauth.OAuthAccessTokenInvalidException
import dev.sumin.skeleton.auth.social.oauth.OAuthNativeToken
import dev.sumin.skeleton.auth.social.oauth.OAuthProviderNotFoundException
import dev.sumin.skeleton.auth.social.oauth.codeExchange
import dev.sumin.skeleton.auth.social.testing.FakeIdp
import dev.sumin.skeleton.common.http.ExternalHttpStatusException
import org.slf4j.LoggerFactory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 앱 SDK 의 네이버 액세스 토큰 검증 (스펙 §3, 결정됨 2) — 진짜 네이버 없이 FakeIdp 로 */
class NaverNativeTokenTest {
    private val secretToken = "naver-app-access-token-SECRET"
    private lateinit var idp: FakeIdp
    private lateinit var provider: NaverOAuthProvider

    @BeforeTest
    fun setUp() {
        idp = FakeIdp()
        idp.stub("/v1/nid/me") { 200 to """{"resultcode":"00","message":"success","response":{"id":"naver-id-1","email":"naver@example.com","nickname":"nick","name":"Naver User"}}""" }
        provider = NaverOAuthProvider(
            FakeIdp.httpClient(),
            AuthSocialProperties.Provider(enabled = true, clientId = "naver-client", clientSecret = "naver-secret", apiBaseUrl = idp.base),
        )
    }

    @AfterTest
    fun tearDown() = idp.close()

    private fun verify() = provider.verifyNativeToken(OAuthNativeToken(secretToken, null))

    @Test
    fun `naver takes an access token and builds the same profile the code flow builds`() {
        assertEquals(NativeTokenKind.ACCESS_TOKEN, provider.nativeToken)
        val profile = verify()
        assertEquals("naver", profile.provider)
        assertEquals("naver-id-1", profile.providerUserId)
        assertEquals("naver@example.com", profile.email)
        assertEquals("Naver User", profile.displayName)
        val request = idp.requests.single { it.path == "/v1/nid/me" }
        assertEquals("Bearer $secretToken", request.headers.entries.first { it.key.equals("authorization", true) }.value.single())
    }

    @Test
    fun `an app-only backend needs no client secret - the token verifies and the code flow is closed`() {
        val appOnly = NaverOAuthProvider(
            FakeIdp.httpClient(),
            AuthSocialProperties.Provider(enabled = true, clientId = "naver-client", apiBaseUrl = idp.base),
        )
        assertEquals("naver-id-1", appOnly.verifyNativeToken(OAuthNativeToken(secretToken, null)).providerUserId)
        assertFalse(appOnly.codeFlow)
        assertFailsWith<OAuthProviderNotFoundException> { appOnly.codeExchange("code", null, null, null) }
        assertTrue(provider.codeFlow)
    }

    @Test
    fun `a token naver rejects is a 401, a naver outage stays an upstream failure`() {
        idp.stub("/v1/nid/me") { 401 to """{"resultcode":"024","message":"Authentication failed"}""" }
        assertFailsWith<OAuthAccessTokenInvalidException> { verify() }
        idp.stub("/v1/nid/me") { 500 to """{"resultcode":"500","message":"Internal Server Error"}""" }
        assertFailsWith<ExternalHttpStatusException> { verify() }
    }

    @Test
    fun `the access token never reaches the logs or the exception text`() {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        root.addAppender(appender)
        val thrown = mutableListOf<Throwable>()
        try {
            idp.stub("/v1/nid/me") { 401 to """{"resultcode":"024","message":"Authentication failed"}""" }
            thrown += assertFailsWith<OAuthAccessTokenInvalidException> { verify() }
            idp.stub("/v1/nid/me") { 500 to """{"message":"internal"}""" }
            thrown += assertFailsWith<ExternalHttpStatusException> { verify() }
        } finally {
            root.detachAppender(appender)
        }
        val texts = appender.list.flatMap { e -> listOf(e.formattedMessage, e.throwableProxy?.message.orEmpty()) } +
            thrown.flatMap { t -> generateSequence(t) { it.cause }.map { it.toString() + (it.message ?: "") }.toList() }
        assertFalse(texts.any { secretToken in it }, "token leaked: $texts")
    }
}
