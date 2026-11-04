package dev.sumin.skeleton.auth.social.kakao

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.sumin.skeleton.auth.social.config.AuthSocialProperties
import dev.sumin.skeleton.auth.social.oauth.NativeTokenKind
import dev.sumin.skeleton.auth.social.oauth.OAuthAccessTokenInvalidException
import dev.sumin.skeleton.auth.social.oauth.OAuthNativeToken
import dev.sumin.skeleton.auth.social.oauth.OAuthProviderNotFoundException
import dev.sumin.skeleton.auth.social.testing.FakeIdp
import dev.sumin.skeleton.common.http.ExternalHttpStatusException
import org.slf4j.LoggerFactory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 앱 SDK 의 카카오 액세스 토큰 검증 (스펙 §3) — 진짜 카카오 없이 FakeIdp 로 */
class KakaoNativeTokenTest {
    private val appId = "123456"
    private val secretToken = "kakao-app-access-token-SECRET"
    private lateinit var idp: FakeIdp
    private lateinit var provider: KakaoOAuthProvider

    @BeforeTest
    fun setUp() {
        idp = FakeIdp()
        idp.stub("/v1/user/access_token_info") { 200 to """{"id":12345,"expires_in":3600,"app_id":$appId,"appId":$appId}""" }
        idp.stub("/v2/user/me") { 200 to """{"id":12345,"kakao_account":{"email":"kakao@example.com","is_email_valid":true,"is_email_verified":true,"profile":{"nickname":"Kakao User"}}}""" }
        provider = providerWith(appId)
    }

    @AfterTest
    fun tearDown() = idp.close()

    private fun providerWith(appId: String?) = KakaoOAuthProvider(
        FakeIdp.httpClient(),
        AuthSocialProperties.Provider(enabled = true, clientId = "kakao-client", clientSecret = "kakao-secret", appId = appId, apiBaseUrl = idp.base),
    )

    private fun verify(token: String = secretToken) = provider.verifyNativeToken(OAuthNativeToken(token, null))

    @Test
    fun `the token entrance opens only when app-id is set`() {
        assertEquals(NativeTokenKind.ACCESS_TOKEN, provider.nativeToken)
        assertNull(providerWith(null).nativeToken)
        assertNull(providerWith("  ").nativeToken)
        assertFailsWith<OAuthProviderNotFoundException> { providerWith(null).verifyNativeToken(OAuthNativeToken(secretToken, null)) }
    }

    @Test
    fun `a token of this app becomes the same profile the code flow builds`() {
        val profile = verify()
        assertEquals("kakao", profile.provider)
        assertEquals("12345", profile.providerUserId)
        assertEquals("kakao@example.com", profile.email)
        assertEquals("Kakao User", profile.displayName)
        assertTrue(profile.emailVerified)
        val info = idp.requests.first { it.path == "/v1/user/access_token_info" }
        assertEquals("Bearer $secretToken", info.headers.entries.first { it.key.equals("authorization", true) }.value.single())
    }

    @Test
    fun `a token issued to another app is rejected`() {
        idp.stub("/v1/user/access_token_info") { 200 to """{"id":12345,"expires_in":3600,"app_id":999,"appId":999}""" }
        assertFailsWith<OAuthAccessTokenInvalidException> { verify() }
        assertEquals(0, idp.count("/v2/user/me"), "no profile is read for a token of another app")
    }

    @Test
    fun `a response without app_id is rejected too`() {
        idp.stub("/v1/user/access_token_info") { 200 to """{"id":12345,"expires_in":3600}""" }
        assertFailsWith<OAuthAccessTokenInvalidException> { verify() }
    }

    @Test
    fun `a token kakao itself rejects is a 401, not a 502`() {
        idp.stub("/v1/user/access_token_info") { 401 to """{"msg":"this access token does not exist","code":-401}""" }
        assertFailsWith<OAuthAccessTokenInvalidException> { verify() }
    }

    @Test
    fun `a kakao outage stays an upstream failure (502), never an invalid token`() {
        idp.stub("/v1/user/access_token_info") { 500 to """{"msg":"internal","code":-1}""" }
        assertFailsWith<ExternalHttpStatusException> { verify() }
    }

    @Test
    fun `the access token never reaches the logs or the exception text`() {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        root.addAppender(appender)
        val thrown = mutableListOf<Throwable>()
        try {
            idp.stub("/v1/user/access_token_info") { 401 to """{"msg":"bad","code":-401}""" }
            thrown += assertFailsWith<OAuthAccessTokenInvalidException> { verify() }
            idp.stub("/v1/user/access_token_info") { 200 to """{"id":1,"app_id":999}""" }
            thrown += assertFailsWith<OAuthAccessTokenInvalidException> { verify() }
            idp.stub("/v1/user/access_token_info") { 500 to """{"msg":"internal"}""" }
            thrown += assertFailsWith<ExternalHttpStatusException> { verify() }
        } finally {
            root.detachAppender(appender)
        }
        val texts = appender.list.flatMap { e -> listOf(e.formattedMessage, e.throwableProxy?.let { p -> p.message.orEmpty() }.orEmpty()) } +
            thrown.flatMap { t -> generateSequence(t) { it.cause }.map { it.toString() + (it.message ?: "") }.toList() }
        assertFalse(texts.any { secretToken in it }, "token leaked: $texts")
    }
}
