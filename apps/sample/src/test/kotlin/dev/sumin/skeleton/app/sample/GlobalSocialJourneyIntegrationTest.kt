package dev.sumin.skeleton.app.sample

import com.jayway.jsonpath.JsonPath
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.sumin.skeleton.account.abuse.AccountTaskRunner
import dev.sumin.skeleton.account.mail.AccountMail
import dev.sumin.skeleton.account.mail.AccountMailer
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.app.sample.notes.FakePresignedStorage
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete

/**
 * 글로벌 소셜(LINE · X)을 진짜 계정 모듈 · PostgreSQL · 보안 체인 위에서, 가짜 제공자 서버(공식 문서 모양의 응답)와 함께:
 * PKCE · nonce 전제, 주소 없는 계정(LINE 이 이메일을 줘도 확인되지 않은 값은 없는 것) 가입 · 연결 · 해제 · 삭제, 같은 이메일의 기존 계정에 병합하지 않기.
 */
@SpringBootTest(properties = ["spring.config.import=classpath:test-seeds.yml", "skeleton.account.mail.link-base-url=https://app.example.com", "skeleton.auth-session.reuse-grace=0s", "skeleton.legal.reconsent.enabled=true"])
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, GlobalSocialJourneyIntegrationTest.Beans::class)
class GlobalSocialJourneyIntegrationTest {
    @TestConfiguration(proxyBeanMethods = false)
    class Beans {
        val sent = CopyOnWriteArrayList<AccountMail>()
        @Bean fun recordingMailer(): AccountMailer = AccountMailer { sent += it }
        @Bean fun directTasks(): AccountTaskRunner = AccountTaskRunner.DIRECT
        @Bean fun fakeStorage(): FakePresignedStorage = FakePresignedStorage()
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var beans: Beans

    private val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
    private val nonce = "line-nonce-0123456789"

    companion object {
        private const val LINE_ID = "2001234567"
        private const val LINE_SECRET = "0123456789abcdef0123456789abcdef"
        private class Code(val challenge: String, val claims: Map<String, Any?>?, val xUser: String?, var used: Boolean = false)
        private val codes = ConcurrentHashMap<String, Code>()
        private val server: HttpServer = HttpServer.create(InetSocketAddress("localhost", 0), 0).also { s ->
            s.createContext("/") { ex -> handle(ex) }
            s.start()
        }
        private val base = "http://localhost:${server.address.port}"

        @JvmStatic
        @DynamicPropertySource
        fun props(r: DynamicPropertyRegistry) {
            val p = "skeleton.auth-social-oidc.providers.line"
            r.add("$p.client-id") { LINE_ID }
            r.add("$p.client-secret") { LINE_SECRET }
            r.add("$p.scopes") { "openid,profile,email" }
            r.add("$p.issuer") { "$base/line" }
            r.add("$p.authorization-endpoint") { "$base/line/authorize" }
            r.add("$p.token-endpoint") { "$base/line/token" }
            r.add("$p.jwks-uri") { "$base/line/certs" }
            r.add("skeleton.auth-social-x.client-id") { "x-client" }
            r.add("skeleton.auth-social-x.client-secret") { "x-secret" }
            r.add("skeleton.auth-social-x.api-base-url") { "$base/x" }
        }

        @JvmStatic
        @AfterAll
        fun stop() = server.stop(0)

        private fun s256(v: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(v.toByteArray()))
        private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        private fun hs256(claims: Map<String, Any?>): String {
            fun json(v: Any?): String = when (v) {
                null -> "null"; is Number, is Boolean -> v.toString(); is List<*> -> v.joinToString(",", "[", "]") { json(it) }
                else -> "\"" + v.toString().replace("\"", "\\\"") + "\""
            }
            val head = b64("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
            val body = b64(claims.entries.joinToString(",", "{", "}") { "\"${it.key}\":${json(it.value)}" }.toByteArray())
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(LINE_SECRET.toByteArray(), "HmacSHA256")) }
            return "$head.$body.${b64(mac.doFinal("$head.$body".toByteArray()))}"
        }

        private fun form(body: String) = body.split("&").filter { it.isNotBlank() }.associate { val p = it.split("=", limit = 2); URLDecoder.decode(p[0], StandardCharsets.UTF_8) to URLDecoder.decode(p.getOrElse(1) { "" }, StandardCharsets.UTF_8) }

        private fun handle(ex: HttpExchange) {
            val body = ex.requestBody.bufferedReader().use { it.readText() }
            when (ex.requestURI.path) {
                "/line/token" -> {
                    val f = form(body)
                    val c = codes[f["code"]]
                    if (f["client_id"] != LINE_ID || f["client_secret"] != LINE_SECRET) return ex.reply(400, """{"error":"invalid_client"}""")
                    if (c == null || c.claims == null || c.used) return ex.reply(400, """{"error":"invalid_grant","error_description":"invalid authorization code"}""")
                    c.used = true
                    if (f["code_verifier"]?.let { s256(it) } != c.challenge) return ex.reply(400, """{"error":"invalid_grant","error_description":"code_verifier does not match"}""")
                    val now = System.currentTimeMillis() / 1000
                    val claims = linkedMapOf<String, Any?>("iss" to "$base/line", "aud" to LINE_ID, "exp" to now + 600, "iat" to now) + c.claims
                    ex.reply(200, """{"access_token":"line-at","expires_in":2592000,"id_token":"${hs256(claims)}","refresh_token":"rt","scope":"profile openid","token_type":"Bearer"}""")
                }
                "/x/2/oauth2/token" -> {
                    val basic = ex.requestHeaders.getFirst("Authorization")?.removePrefix("Basic ")?.let { String(Base64.getDecoder().decode(it)) }
                    val f = form(body)
                    val c = codes[f["code"]]
                    if (basic != "x-client:x-secret") return ex.reply(401, """{"error":"unauthorized_client"}""")
                    if (c == null || c.xUser == null || c.used) return ex.reply(400, """{"error":"invalid_request","error_description":"Value passed for the authorization code was invalid."}""")
                    c.used = true
                    if (f["code_verifier"]?.let { s256(it) } != c.challenge) return ex.reply(400, """{"error":"invalid_request","error_description":"Value passed for the code verifier was invalid."}""")
                    ex.reply(200, """{"token_type":"bearer","expires_in":7200,"access_token":"x-at-${c.xUser}","scope":"users.read tweet.read"}""")
                }
                "/x/2/users/me" -> {
                    val user = ex.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer x-at-")
                    ex.reply(200, """{"data":{"id":"$user","name":"X $user","username":"user_$user","profile_image_url":"https://pbs.twimg.com/p_normal.jpg"}}""")
                }
                else -> ex.reply(404, "{}")
            }
        }

        private fun HttpExchange.reply(status: Int, body: String) {
            responseHeaders.add("Content-Type", "application/json")
            val b = body.toByteArray(); sendResponseHeaders(status, b.size.toLong()); responseBody.use { it.write(b) }
        }
    }

    private fun lineCode(sub: String, email: String? = null, claimNonce: String = nonce): String {
        val code = "lc-${UUID.randomUUID()}"
        val claims = mutableMapOf<String, Any?>("sub" to sub, "nonce" to claimNonce, "name" to "Line $sub", "picture" to "https://profile.line-scdn.net/$sub", "amr" to listOf("pwd"))
        email?.let { claims["email"] = it }
        codes[code] = Code(s256(verifier), claims, null)
        return code
    }

    private fun xCode(user: String): String = "xc-${UUID.randomUUID()}".also { codes[it] = Code(s256(verifier), null, user) }

    private fun post(path: String, body: String, bearer: String? = null) = mvc.post(path) {
        contentType = MediaType.APPLICATION_JSON; content = body
        bearer?.let { header("Authorization", "Bearer $it") }
        header("Idempotency-Key", UUID.randomUUID().toString())
    }

    private fun field(json: String, path: String): String = JsonPath.read<Any>(json, path).toString()
    private fun lineLogin(code: String, v: String? = verifier, n: String? = nonce) =
        post("/api/v1/auth/social/line/login", """{"authorizationCode":"$code","redirectUri":"https://app.example.com/cb/line"${v?.let { ""","codeVerifier":"$it"""" } ?: ""}${n?.let { ""","nonce":"$it"""" } ?: ""}}""")
    private fun xLogin(code: String) = post("/api/v1/auth/social/x/login", """{"authorizationCode":"$code","redirectUri":"https://app.example.com/cb/x","codeVerifier":"$verifier"}""")
    private fun lineReauth(sub: String) = """{"provider":"line","authorizationCode":"${lineCode(sub)}","redirectUri":"https://app.example.com/cb/line","codeVerifier":"$verifier","nonce":"$nonce"}"""

    @Test
    fun `methods lists exactly the providers whose client id is set, with pkce nonce and authorize endpoints`() {
        mvc.get("/api/v1/auth/methods").andExpect {
            status { isOk() }
            jsonPath("$.value.social[?(@.provider=='line')].pkce") { value("REQUIRED") }
            jsonPath("$.value.social[?(@.provider=='line')].nonce") { value("REQUIRED") }
            jsonPath("$.value.social[?(@.provider=='line')].authorize.scopes[2]") { value("email") }
            jsonPath("$.value.social[?(@.provider=='line')].authorize.url") { value("$base/line/authorize") }
            jsonPath("$.value.social[?(@.provider=='x')].pkce") { value("REQUIRED") }
            jsonPath("$.value.social[?(@.provider=='x')].authorize.url") { value("https://x.com/i/oauth2/authorize") }
            jsonPath("$.value.social[?(@.provider=='x')].authorize.scopes[0]") { value("users.read") }
            jsonPath("$.value.social.length()") { value(2) }
        }
    }

    @Test
    fun `LINE needs PKCE and nonce, and a token with a foreign nonce is rejected`() {
        val code = lineCode("Upkce1")
        lineLogin(code, v = null).andExpect { status { isBadRequest() }; jsonPath("$.code") { value("AUTH.SOCIAL_PKCE_FAILED") } }
        lineLogin(code, n = null).andExpect { status { isBadRequest() }; jsonPath("$.code") { value("AUTH.SOCIAL_NONCE_FAILED") } }
        lineLogin(lineCode("Upkce1", claimNonce = "someone-elses-nonce-000")).andExpect { status { isUnauthorized() }; jsonPath("$.code") { value("AUTH.SOCIAL_ID_TOKEN_INVALID") } }
        // the first code was never spent by the refused attempts above: it still works
        lineLogin(code).andExpect { status { isOk() } }
        // single use
        lineLogin(code).andExpect { status { isUnauthorized() }; jsonPath("$.code") { value("AUTH_SOCIAL.INVALID_AUTHORIZATION_CODE") } }
    }

    @Test
    fun `a LINE email is never trusted - no merge into the password account with the same address, and the LINE account has no address`() {
        val email = "victim-${System.nanoTime()}@example.com"
        val signUp = post("/api/v1/account/sign-up", """{"email":"$email","password":"tangerine-42-moon","consents":[{"type":"terms","version":"sample-1"},{"type":"privacy","version":"sample-1"}]}""").andExpect { status { isAccepted() } }.andReturn().response.contentAsString
        val verified = post("/api/v1/auth/verify-email", """{"signUpId":"${field(signUp, "$.value.signUpId")}","code":"${beans.sent.last { it.kind == MailKind.VERIFY_CODE && it.to == email }.vars.getValue("code")}"}""").andExpect { status { isOk() } }.andReturn().response.contentAsString
        val victimId = field(verified, "$.value.principal.accountId")

        val line = lineLogin(lineCode("Uattacker", email = email)).andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertNotEquals(victimId, field(line, "$.value.principal.accountId"), "an unverified provider email must not merge")
        assertEquals("", field(line, "$.value.principal.email"), "the unverified address is not stored")
    }

    @Test
    fun `an address-less LINE account lives the whole lifecycle - link X, unlink it, delete - re-authenticating with a fresh LINE consent each time`() {
        val signIn = lineLogin(lineCode("Ulife1")).andExpect { status { isOk() } }.andReturn().response.contentAsString
        val bearer = field(signIn, "$.value.accessToken")
        agree(bearer)
        // the same LINE user signs in again to the same account
        assertEquals(field(signIn, "$.value.principal.accountId"), field(lineLogin(lineCode("Ulife1")).andReturn().response.contentAsString, "$.value.principal.accountId"))

        // link X: without any proof an address-less account is refused, with a fresh LINE code (+ its verifier and nonce) it works
        val linkBody = { proof: String? -> """{"authorizationCode":"${xCode("x-life-1")}","redirectUri":"https://app.example.com/cb/x","codeVerifier":"$verifier"${proof?.let { ""","socialReauth":$it""" } ?: ""}}""" }
        post("/api/v1/account/identities/social/x", linkBody(null), bearer).andExpect { status { isForbidden() }; jsonPath("$.code") { value("ACCOUNT.REAUTH_REQUIRED") } }
        post("/api/v1/account/identities/social/x", linkBody(lineReauth("Ulife1").replace(""","nonce":"$nonce"""", "")), bearer).andExpect { status { isBadRequest() }; jsonPath("$.code") { value("AUTH.SOCIAL_NONCE_FAILED") } }
        post("/api/v1/account/identities/social/x", linkBody(lineReauth("Uother")), bearer).andExpect { status { isBadRequest() }; jsonPath("$.code") { value("ACCOUNT.REAUTH_FAILED") } }
        post("/api/v1/account/identities/social/x", linkBody(lineReauth("Ulife1")), bearer).andExpect { status { isCreated() }; jsonPath("$.value.method") { value("x") } }
        // both providers now reach the same account
        assertEquals(field(signIn, "$.value.principal.accountId"), field(xLogin(xCode("x-life-1")).andExpect { status { isOk() } }.andReturn().response.contentAsString, "$.value.principal.accountId"))

        // unlink X (re-auth again), then delete the account (re-auth again)
        val identities = mvc.get("/api/v1/account/identities") { header("Authorization", "Bearer $bearer") }.andReturn().response.contentAsString
        val xId = JsonPath.read<List<String>>(identities, "$.values[?(@.method=='x')].id").single()
        mvc.perform(delete("/api/v1/account/identities/$xId").header("Authorization", "Bearer $bearer").contentType(MediaType.APPLICATION_JSON).content("""{"socialReauth":${lineReauth("Ulife1")}}""")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent)
        post("/api/v1/account/delete", """{"socialReauth":${lineReauth("Ulife1")}}""", bearer).andExpect { status { isAccepted() }; jsonPath("$.value.status") { value("DELETION_SCHEDULED") } }
    }

    /** 소셜로 처음 만든 계정의 첫 로그인 뒤 동의 화면이 하는 일 (docs/legal-http-contract.md "First sign-in that creates the account") */
    private fun agree(bearer: String) = post(
        "/api/v1/legal/consents", """{"consents":[{"type":"terms","version":"sample-1"},{"type":"privacy","version":"sample-1"}],"source":"first-sign-in"}""", bearer,
    ).andExpect { status { isOk() }; jsonPath("$.value.blocked") { value(false) } }

    @Test
    fun `a first LINE or X sign-in creates an account that is blocked by legal consent until it agrees`() {
        for (login in listOf({ lineLogin(lineCode("Ulegal1")) }, { xLogin(xCode("x-legal-1")) })) {
            val bearer = field(login().andExpect { status { isOk() } }.andReturn().response.contentAsString, "$.value.accessToken")
            mvc.get("/api/v1/notes") { header("Authorization", "Bearer $bearer") }.andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value("LEGAL.RECONSENT_REQUIRED") }
                jsonPath("$.data.missing.length()") { value(2) }
            }
            mvc.get("/api/v1/legal/consents/me") { header("Authorization", "Bearer $bearer") }.andExpect { status { isOk() }; jsonPath("$.value.blocked") { value(true) } }
            agree(bearer)
            mvc.get("/api/v1/notes") { header("Authorization", "Bearer $bearer") }.andExpect { status { isOk() } }
        }
    }

    @Test
    fun `X signs a user up without an address and finds the same account next time`() {
        val first = xLogin(xCode("x-solo-1")).andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertEquals("", field(first, "$.value.principal.email"))
        assertEquals(field(first, "$.value.principal.accountId"), field(xLogin(xCode("x-solo-1")).andReturn().response.contentAsString, "$.value.principal.accountId"))
        post("/api/v1/auth/social/x/login", """{"authorizationCode":"${xCode("x-solo-1")}"}""").andExpect { status { isBadRequest() }; jsonPath("$.code") { value("AUTH.SOCIAL_PKCE_FAILED") } }
    }
}
