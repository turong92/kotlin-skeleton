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
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post

/**
 * 이 앱의 방식 — 이메일은 **쓰기 때**(`email.required-when: WRITE`), 로그인 아이디는 켬 — 의 한 바퀴를 진짜 PostgreSQL · 보안 체인 · 모든 모듈과 함께, 소셜(X) 가짜 제공자 서버로 (스펙 §16 조립 — 이메일 "쓰기 때"):
 * 소셜 첫 로그인 → 아이디(읽기까지 막는다) → 약관 → **읽기 전부 통과**(글 · 알림함 · 첨부 보기 · 알림 읽음 표시 · 언어 바꾸기) → 글쓰기 403 →
 * 이메일 등록(방금 연 세션: 재인증 없음 / 오래된 세션: 403 REAUTH_REQUIRED → 제공자 재확인) → **같은 글쓰기 호출**(같은 Idempotency-Key)이 통과.
 * 버리기: 아이디를 정하기 전 · 정한 뒤 모두 되고, 정한 아이디는 다시 못 쓴다. ("로그인 직후" 방식의 길은 EmailRequiredJourneyIntegrationTest.)
 */
@SpringBootTest(properties = ["spring.config.import=classpath:test-seeds.yml", "skeleton.account.mail.link-base-url=https://app.example.com", "skeleton.legal.reconsent.enabled=true"])
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, EmailOnWriteJourneyIntegrationTest.Beans::class)
class EmailOnWriteJourneyIntegrationTest {
    @TestConfiguration(proxyBeanMethods = false)
    class Beans {
        val sent = CopyOnWriteArrayList<AccountMail>()
        @Bean fun recordingMailer(): AccountMailer = AccountMailer { sent += it }
        @Bean fun directTasks(): AccountTaskRunner = AccountTaskRunner.DIRECT
        @Bean fun fakeStorage(): FakePresignedStorage = FakePresignedStorage()
    }

    companion object {
        private const val REDIRECT = "https://app.example.com/cb/x"
        private const val VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"

        /** X 의 가짜 서버: 코드 `xc-<사용자>#<번호>` → 토큰 `x-at-<사용자>` → 그 사용자. 이메일은 주지 않는다(X 는 주지 않는다) — 만들어지는 계정은 주소가 없다 */
        private val server: HttpServer = HttpServer.create(InetSocketAddress("localhost", 0), 0).also { s ->
            s.createContext("/") { ex -> handle(ex) }
            s.start()
        }
        private val base = "http://localhost:${server.address.port}"

        @JvmStatic
        @DynamicPropertySource
        fun props(r: DynamicPropertyRegistry) {
            r.add("skeleton.auth-social-x.client-id") { "x-client" }
            r.add("skeleton.auth-social-x.client-secret") { "x-secret" }
            r.add("skeleton.auth-social-x.api-base-url") { "$base/x" }
        }

        @JvmStatic
        @AfterAll
        fun stop() = server.stop(0)

        private fun handle(ex: HttpExchange) {
            val body = ex.requestBody.bufferedReader().use { it.readText() }
            when (ex.requestURI.path) {
                "/x/2/oauth2/token" -> {
                    val code = body.split("&").map { it.split("=", limit = 2) }.firstOrNull { it[0] == "code" }?.getOrNull(1)?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8) }
                    val user = code?.removePrefix("xc-")?.substringBefore('#')
                    if (code == null || !code.startsWith("xc-")) ex.reply(400, """{"error":"invalid_request"}""")
                    else ex.reply(200, """{"token_type":"bearer","expires_in":7200,"access_token":"x-at-$user","scope":"users.read tweet.read"}""")
                }
                "/x/2/users/me" -> {
                    val user = ex.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer x-at-")
                    ex.reply(200, """{"data":{"id":"$user","name":"X $user","username":"user_$user"}}""")
                }
                else -> ex.reply(404, "{}")
            }
        }

        private fun HttpExchange.reply(status: Int, body: String) {
            responseHeaders.add("Content-Type", "application/json")
            val b = body.toByteArray(); sendResponseHeaders(status, b.size.toLong()); responseBody.use { it.write(b) }
        }
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var beans: Beans
    @Autowired lateinit var jdbc: JdbcClient

    private var codes = 0
    private fun freshCode(user: String) = "xc-$user#${++codes}"

    private fun get(path: String, bearer: String) = mvc.get(path) { header("Authorization", "Bearer $bearer") }

    private fun post(path: String, body: String, bearer: String? = null, key: String? = null) = mvc.post(path) {
        if (path == "/api/v1/account/login-id") with { it.remoteAddr = TestClientIps.fresh(); it }   // 정하기는 IP 별로도 센다 — 시험마다 새 주소
        contentType = MediaType.APPLICATION_JSON; content = body
        bearer?.let { header("Authorization", "Bearer $it") }
        key?.let { header("Idempotency-Key", it) }
    }

    private fun patch(path: String, body: String = "{}", bearer: String) = mvc.patch(path) {
        contentType = MediaType.APPLICATION_JSON; content = body; header("Authorization", "Bearer $bearer")
    }

    private fun blocked(r: ResultActionsDsl, code: String) = r.andExpect { status { isForbidden() }; jsonPath("$.code") { value(code) } }
    private fun field(json: String, path: String): String = JsonPath.read<Any>(json, path).toString()
    private fun reauth(user: String) = """{"provider":"x","authorizationCode":"${freshCode(user)}","redirectUri":"$REDIRECT","codeVerifier":"$VERIFIER"}"""

    /** 소셜로 처음 들어온 사용자 — (접근 토큰, 계정 id). 제공자가 이메일을 주지 않아 주소 없는 계정이 만들어진다 */
    private fun socialFirstLogin(user: String): Pair<String, String> {
        val r = post("/api/v1/auth/social/x/login", """{"authorizationCode":"${freshCode(user)}","redirectUri":"$REDIRECT","codeVerifier":"$VERIFIER"}""").andExpect { status { isOk() } }.andReturn().response.contentAsString
        return field(r, "$.value.accessToken") to field(r, "$.value.principal.accountId")
    }

    private fun claimId(bearer: String, loginId: String) =
        post("/api/v1/account/login-id", """{"loginId":"$loginId"}""", bearer).andExpect { status { isOk() }; jsonPath("$.value.loginId") { value(loginId) } }

    private fun agreeToTerms(bearer: String) = post(
        "/api/v1/legal/consents", """{"consents":[{"type":"terms","version":"sample-1"},{"type":"privacy","version":"sample-1"}],"source":"first-sign-in"}""", bearer,
    ).andExpect { status { isOk() }; jsonPath("$.value.blocked") { value(false) } }

    private fun uniqueId(prefix: String) = "$prefix.${System.nanoTime() % 1_000_000_000}"

    private fun codeFor(email: String) = beans.sent.last { it.kind == MailKind.EMAIL_CHANGE_CODE && it.to == email }.vars.getValue("code")

    /** 쓰기 한 건 — 이 호출이 403 → 등록 → 같은 키로 통과하는 것을 본다 */
    private fun writePost(bearer: String, profileId: String, key: String) =
        post("/api/v1/boards/general/posts", """{"title":"쓰기 때 글","body":"본문","authorId":"$profileId"}""", bearer, key)

    @Test
    fun `social first sign-in, login ID, terms, every read passes, the write is refused until the address is registered, then the same call goes through`() {
        val (bearer, accountId) = socialFirstLogin("ow-fresh")

        // 방식 표시: 주소가 없고 이 앱은 쓰기 때라 emailRequired 는 false, emailRequiredToWrite 가 true. 아이디는 아직 없다(키는 있고 값이 null)
        get("/api/v1/account/me", bearer).andExpect {
            status { isOk() }
            jsonPath("$.value.emailRequired") { value(false) }
            jsonPath("$.value.emailRequiredToWrite") { value(true) }
            jsonPath("$.value.loginId") { value(null as String?) }
        }

        // 1. 아이디가 맨 앞이다: 허용 목록 밖은 읽기도, 쓰기 때 열어 둔 변경(알림 읽음 표시 · presign-download)도 아이디 게이트가 먼저 답한다
        blocked(get("/api/v1/notes", bearer), "ACCOUNT.LOGIN_ID_REQUIRED")
        blocked(get("/api/v1/boards/general/posts", bearer), "ACCOUNT.LOGIN_ID_REQUIRED")
        blocked(get("/api/v1/notifications", bearer), "ACCOUNT.LOGIN_ID_REQUIRED")
        blocked(patch("/api/v1/notifications/read-all", bearer = bearer), "ACCOUNT.LOGIN_ID_REQUIRED")
        blocked(post("/api/v1/storage/presign-download", """{"key":"uploads/none"}""", bearer), "ACCOUNT.LOGIN_ID_REQUIRED")
        blocked(patch("/api/v1/account/me", """{"locale":"en"}""", bearer), "ACCOUNT.LOGIN_ID_REQUIRED")   // 계정 살림(PATCH me)은 이메일 쪽 허용이지 아이디 쪽 허용이 아니다
        post("/api/v1/account/login-id/availability", """{"loginId":"ow.fresh.1"}""", bearer).andExpect { status { isOk() } }   // 정하기의 실시간 확인은 열려 있다

        // 2. 아이디를 정한다 — 이메일 없이도 된다. 그 다음 관문은 약관(이 시험은 재동의를 켰다)이고, 이메일은 읽기에서 묻지 않는다
        val loginId = uniqueId("ow.fresh")
        claimId(bearer, loginId)
        blocked(get("/api/v1/notes", bearer), "LEGAL.RECONSENT_REQUIRED")
        agreeToTerms(bearer)

        // 3. 읽기 전부 통과 — 주소가 없는데도. (이 계정은 닉네임이 없다: 언어 · 닉네임 바꾸기도 PATCH 라 "쓰기 때"의 고정 허용이다)
        get("/api/v1/notes", bearer).andExpect { status { isOk() } }
        get("/api/v1/boards/general/posts", bearer).andExpect { status { isOk() } }
        get("/api/v1/notifications", bearer).andExpect { status { isOk() } }
        get("/api/v1/account/identities", bearer).andExpect { status { isOk() } }
        patch("/api/v1/notifications/read-all", bearer = bearer).andExpect { status { isOk() } }                        // 알림함을 여는 것은 읽기인데 화면이 읽음 표시를 보낸다
        patch("/api/v1/notifications/evt_none/read", bearer = bearer).andExpect { status { isNotFound() } }                // 없는 알림 — 게이트가 아니라 핸들러가 답한다
        post("/api/v1/storage/presign-download", """{"key":"uploads/none/x.png"}""", bearer).andExpect { status { isNotFound() } }   // 첨부 보기: 남의/없는 키는 404 — 게이트가 아니라 핸들러가 답한다
        patch("/api/v1/account/me", """{"locale":"en","displayName":"Writer"}""", bearer).andExpect { status { isOk() }; jsonPath("$.value.locale") { value("en") }; jsonPath("$.value.displayName") { value("Writer") } }
        val profileId = field(get("/api/v1/author-profiles", bearer).andExpect { status { isOk() } }.andReturn().response.contentAsString, "$.values[0].id")

        // 4. 쓰기는 403 — 글 · 노트 · 첨부 올리기 · 내보내기. 호출을 붙잡아 둘 수 있게 같은 Idempotency-Key 로 다시 보낼 것이다
        val key = UUID.randomUUID().toString()
        blocked(writePost(bearer, profileId, key), "ACCOUNT.EMAIL_REQUIRED")
        blocked(post("/api/v1/notes", """{"title":"t","body":"b"}""", bearer, UUID.randomUUID().toString()), "ACCOUNT.EMAIL_REQUIRED")
        blocked(post("/api/v1/storage/presign", """{"fileName":"photo.png","contentType":"image/png","sizeBytes":1024}""", bearer), "ACCOUNT.EMAIL_REQUIRED")
        // 읽기는 그 뒤에도 그대로
        get("/api/v1/boards/general/posts", bearer).andExpect { status { isOk() } }

        // 5. 이메일 등록 — 방금 연 세션(1시간 창 안)이라 재인증이 없다
        val email = "ow-${System.nanoTime()}@example.com"
        post("/api/v1/account/email/change", """{"newEmail":"$email"}""", bearer, UUID.randomUUID().toString()).andExpect { status { isAccepted() }; jsonPath("$.value.status") { value("VERIFICATION_SENT") } }
        blocked(writePost(bearer, profileId, key), "ACCOUNT.EMAIL_REQUIRED")   // 주소를 적은 것만으로는 부족하다 — 코드가 돌아와야 한다
        post("/api/v1/account/email/change/confirm", """{"code":"${codeFor(email)}"}""", bearer).andExpect { status { isNoContent() } }
        get("/api/v1/account/me", bearer).andExpect {
            status { isOk() }
            jsonPath("$.value.email") { value(email) }
            jsonPath("$.value.emailRequired") { value(false) }
            jsonPath("$.value.emailRequiredToWrite") { value(false) }
            jsonPath("$.value.loginId") { value(loginId) }
        }

        // 6. 붙잡아 둔 같은 호출(같은 키)이 그대로 통과한다 — 403 은 멱등 키를 태우지 않았다
        writePost(bearer, profileId, key).andExpect { status { isCreated() }; jsonPath("$.value.title") { value("쓰기 때 글") } }
        post("/api/v1/notes", """{"title":"t","body":"b"}""", bearer, UUID.randomUUID().toString()).andExpect { status { isCreated() } }
        assertTrue(accountId.isNotBlank())
    }

    @Test
    fun `an old session registering the first address is sent to the provider (REAUTH_REQUIRED), and a fresh provider code lets it through`() {
        val (bearer, accountId) = socialFirstLogin("ow-old")
        claimId(bearer, uniqueId("ow.old"))
        agreeToTerms(bearer)
        patch("/api/v1/account/me", """{"displayName":"Old Timer"}""", bearer).andExpect { status { isOk() } }
        val profileId = field(get("/api/v1/author-profiles", bearer).andReturn().response.contentAsString, "$.values[0].id")
        val key = UUID.randomUUID().toString()
        blocked(writePost(bearer, profileId, key), "ACCOUNT.EMAIL_REQUIRED")

        // 며칠 읽다가 처음 쓰는 사람의 세션: 로그인으로 연 지 1시간이 지났다 (세션이 열린 시각을 옮긴다)
        assertEquals(1, jdbc.sql("update auth_sessions set created_at = :t where account_id = :id").param("t", java.sql.Timestamp.from(Instant.now().minus(3, ChronoUnit.HOURS))).param("id", accountId).update())
        val email = "ow-old-${System.nanoTime()}@example.com"
        blocked(post("/api/v1/account/email/change", """{"newEmail":"$email"}""", bearer, UUID.randomUUID().toString()), "ACCOUNT.REAUTH_REQUIRED")
        // 다른 사람의 제공자 증명은 받지 않는다
        post("/api/v1/account/email/change", """{"newEmail":"$email","socialReauth":${reauth("someone-else")}}""", bearer, UUID.randomUUID().toString())
            .andExpect { status { isBadRequest() }; jsonPath("$.code") { value("ACCOUNT.REAUTH_FAILED") } }
        // 제공자 재확인(지금 새로 받은 코드) → 코드 메일 → 확인
        post("/api/v1/account/email/change", """{"newEmail":"$email","socialReauth":${reauth("ow-old")}}""", bearer, UUID.randomUUID().toString()).andExpect { status { isAccepted() } }
        post("/api/v1/account/email/change/confirm", """{"code":"${codeFor(email)}"}""", bearer).andExpect { status { isNoContent() } }
        writePost(bearer, profileId, key).andExpect { status { isCreated() } }
    }

    @Test
    fun `discard works before and after the login ID is chosen, and a chosen ID can never be taken again`() {
        // 아이디를 정하기 전 — 아이디 게이트가 막은 계정도 버릴 수 있다 (아이디 하나를 헛되이 태우지 않고 나가는 길)
        val (before, beforeId) = socialFirstLogin("ow-discard-a")
        blocked(get("/api/v1/notes", before), "ACCOUNT.LOGIN_ID_REQUIRED")
        post("/api/v1/account/discard", "{}", before, UUID.randomUUID().toString()).andExpect { status { isNoContent() } }
        assertEquals("ERASED", jdbc.sql("select status from accounts where id = :id").param("id", beforeId).query(String::class.java).single())

        // 아이디를 정한 뒤 — 같이 지워지고 아이디는 다시 쓸 수 없다
        val (after, afterId) = socialFirstLogin("ow-discard-b")
        val burned = uniqueId("burned")
        claimId(after, burned)
        agreeToTerms(after)
        get("/api/v1/notes", after).andExpect { status { isOk() } }   // 읽기는 이메일 없이 된다 — 그래도 버릴 수 있다 (조건 그대로: 주소를 가진 적이 없는 활성 계정)
        post("/api/v1/account/discard", "{}", after, UUID.randomUUID().toString()).andExpect { status { isNoContent() } }
        assertEquals("ERASED", jdbc.sql("select status from accounts where id = :id").param("id", afterId).query(String::class.java).single())
        assertEquals(null, jdbc.sql("select login_id from accounts where id = :id").param("id", afterId).query(String::class.java).optional().orElse(null), "the readable ID went with the account")

        post("/api/v1/account/login-id/availability", """{"loginId":"$burned"}""").andExpect { status { isOk() }; jsonPath("$.value.available") { value(false) }; jsonPath("$.value.reason") { value("TAKEN") } }
        val (newcomer, _) = socialFirstLogin("ow-discard-c")
        post("/api/v1/account/login-id", """{"loginId":"$burned"}""", newcomer).andExpect { status { isConflict() }; jsonPath("$.code") { value("ACCOUNT.LOGIN_ID_TAKEN") } }
    }
}
