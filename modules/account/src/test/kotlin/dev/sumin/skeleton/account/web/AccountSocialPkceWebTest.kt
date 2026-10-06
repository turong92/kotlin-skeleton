package dev.sumin.skeleton.account.web

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.account.RecordingMailer
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.accounttest.AccountTestBeans
import dev.sumin.skeleton.accounttest.AccountWebTestApplication
import dev.sumin.skeleton.auth.social.oauth.NonceMode
import dev.sumin.skeleton.auth.social.oauth.OAuthAuthorizeInfo
import dev.sumin.skeleton.auth.social.oauth.OAuthCodeExchange
import dev.sumin.skeleton.auth.social.oauth.OAuthInvalidAuthorizationCodeException
import dev.sumin.skeleton.auth.social.oauth.OAuthProvider
import dev.sumin.skeleton.auth.social.oauth.OAuthUserProfile
import dev.sumin.skeleton.auth.social.oauth.PkceMode
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** PKCE 가 필요한 제공자(`pkceidp`)로 로그인 · 연결 · 다시 인증 · `/auth/methods` 를 HTTP 로 — "FINAL-3 + social PKCE" 부록 */
class PkceIdp : OAuthProvider {
    override val providerId = "pkceidp"
    override val pkce = PkceMode.REQUIRED
    override val nonce = NonceMode.SUPPORTED
    override val autoEnabled = true
    override val publicClientId = "pk-client"
    override val publicRedirectUri = "https://app.example.com/cb/pkceidp"
    override val authorize = OAuthAuthorizeInfo("https://idp.example.com/authorize", listOf("openid", "profile"), mapOf("response_type" to "code"))
    val seen: MutableList<OAuthCodeExchange> = Collections.synchronizedList(mutableListOf())

    override fun fetchProfile(authorizationCode: String, redirectUri: String?): OAuthUserProfile = error("PKCE 경로만")
    override fun fetchProfile(exchange: OAuthCodeExchange): OAuthUserProfile {
        seen += exchange
        return when (exchange.authorizationCode) {
            "p-new" -> OAuthUserProfile("pkceidp", "pk-new", "pk-new@example.com", "pk-new", "Pk New", emailVerified = true, avatarUrl = "https://img.example.com/a.png")
            "p-link" -> OAuthUserProfile("pkceidp", "pk-link", null, null, "Pk Link")
            "p-noemail", "p-noemail-again" -> OAuthUserProfile("pkceidp", "pk-noemail", null, null, "No Mail")
            else -> throw OAuthInvalidAuthorizationCodeException("pkceidp")
        }
    }
}

@TestConfiguration(proxyBeanMethods = false)
class PkceIdpBeans {
    @Bean fun pkceIdp(): PkceIdp = PkceIdp()
}

@SpringBootTest(
    classes = [AccountWebTestApplication::class],
    properties = [
        "skeleton.account.mail.link-base-url=https://app.example.com",
        "skeleton.account.password.bcrypt-strength=4",
        "skeleton.account.social.sign-up=true",
    ],
)
@AutoConfigureMockMvc
@Import(AccountTestBeans::class, PkceIdpBeans::class)
class AccountSocialPkceWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mail: RecordingMailer
    @Autowired lateinit var idp: PkceIdp

    private val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
    private fun login(body: String) = mvc.perform(post("/api/v1/auth/social/pkceidp/login").contentType(MediaType.APPLICATION_JSON).content(body))
    private fun bearer(r: MvcResult) = "Bearer " + JsonPath.read<String>(r.response.contentAsString, "$.value.accessToken")

    @Test
    fun `GET auth methods exposes per provider pkce nonce and the authorize endpoint so the frontend hard codes no provider URL`() {
        mvc.perform(get("/api/v1/auth/methods")).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.social[0].provider").value("pkceidp"))
            .andExpect(jsonPath("$.value.social[0].clientId").value("pk-client"))
            .andExpect(jsonPath("$.value.social[0].redirectUri").value("https://app.example.com/cb/pkceidp"))
            .andExpect(jsonPath("$.value.social[0].pkce").value("REQUIRED"))
            .andExpect(jsonPath("$.value.social[0].nonce").value("SUPPORTED"))
            .andExpect(jsonPath("$.value.social[0].authorize.url").value("https://idp.example.com/authorize"))
            .andExpect(jsonPath("$.value.social[0].authorize.scopes[1]").value("profile"))
            .andExpect(jsonPath("$.value.social[0].authorize.params.response_type").value("code"))
    }

    @Test
    fun `social login without the verifier is 400 AUTH SOCIAL_PKCE_FAILED and with it the verifier reaches the provider`() {
        idp.seen.clear()
        login("""{"authorizationCode":"p-new","redirectUri":"https://app.example.com/cb/pkceidp"}""")
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("AUTH.SOCIAL_PKCE_FAILED"))
        assertEquals(0, idp.seen.size, "the provider (and its single-use code) was not touched")
        login("""{"authorizationCode":"p-new","codeVerifier":"short"}""").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("AUTH.SOCIAL_PKCE_FAILED"))
        login("""{"authorizationCode":"p-new","codeVerifier":"$verifier","nonce":"n-0S6_WzA2Mj"}""").andExpect(status().isOk)
        assertEquals(verifier, idp.seen.single().codeVerifier)
        assertEquals("n-0S6_WzA2Mj", idp.seen.single().nonce)
    }

    @Test
    fun `linking needs the verifier too, and a missing one burns neither the code nor the re-authentication`() {
        val auth = bearer(login("""{"authorizationCode":"p-new","codeVerifier":"$verifier"}""").andReturn())
        mail.sent.clear()
        mvc.perform(post("/api/v1/account/reauth/confirmation").header("Authorization", auth)).andExpect(status().isAccepted)
        val code = mail.of(MailKind.REAUTH_CODE).single().vars.getValue("code")
        val link = { verifierJson: String -> mvc.perform(post("/api/v1/account/identities/social/pkceidp").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content("""{"authorizationCode":"p-link","confirmationCode":"$code"$verifierJson}""")) }
        // the account is already linked to pkceidp (it signed up with it), so a link attempt reaches the exchange and then answers IDENTITY_EXISTS or similar;
        // what matters here: the PKCE precondition answers FIRST and keeps the proof usable
        link("").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("AUTH.SOCIAL_PKCE_FAILED"))
        link(",\"codeVerifier\":\"$verifier\"").andExpect(status().isConflict).andExpect(jsonPath("$.code").value("ACCOUNT.IDENTITY_EXISTS"))
    }

    @Test
    fun `an address-less account re-authenticates with a fresh code and the verifier - and without the verifier it is the PKCE error not a failed proof`() {
        val auth = bearer(login("""{"authorizationCode":"p-noemail","codeVerifier":"$verifier"}""").andReturn())
        val delete = { reauthJson: String -> mvc.perform(post("/api/v1/account/delete").header("Authorization", auth).header("Idempotency-Key", java.util.UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content("""{"socialReauth":$reauthJson}""")) }
        delete("""{"provider":"pkceidp","authorizationCode":"p-noemail-again"}""").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("AUTH.SOCIAL_PKCE_FAILED"))
        delete("""{"provider":"pkceidp","authorizationCode":"p-noemail-again","codeVerifier":"$verifier"}""").andExpect(status().isAccepted).andExpect(jsonPath("$.value.status").value("DELETION_SCHEDULED"))
        assertEquals(verifier, idp.seen.last().codeVerifier)
    }
}
