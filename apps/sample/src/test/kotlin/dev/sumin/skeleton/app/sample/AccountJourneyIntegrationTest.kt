package dev.sumin.skeleton.app.sample

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.account.AccountPurgeService
import dev.sumin.skeleton.account.abuse.AccountTaskRunner
import dev.sumin.skeleton.account.mail.AccountMail
import dev.sumin.skeleton.account.mail.AccountMailer
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.app.sample.notes.FakePresignedStorage
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * 가입 → 이메일 확인 → 로그인 → 새로고침(회전 · 재사용 탐지) → 비밀번호 변경 → 세션 목록 → 삭제 → (유예가 끝난 뒤) 지우기까지, 진짜 PostgreSQL · 보안 체인 · 모듈들과 함께.
 * 삭제된 계정의 게시판 글은 "삭제된 사용자" 로 남는다 (board 의 AccountErasureListener).
 */
@SpringBootTest(properties = ["spring.config.import=classpath:test-seeds.yml", "skeleton.account.mail.link-base-url=https://app.example.com", "skeleton.auth-session.reuse-grace=0s"])
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, AccountJourneyIntegrationTest.Mails::class, AccountJourneyIntegrationTest.Storage::class)
class AccountJourneyIntegrationTest {
    @TestConfiguration(proxyBeanMethods = false)
    class Mails {
        val sent = CopyOnWriteArrayList<AccountMail>()
        @Bean fun recordingMailer(): AccountMailer = AccountMailer { sent += it }
        @Bean fun directTasks(): AccountTaskRunner = AccountTaskRunner.DIRECT
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Storage { @Bean fun fakeStorage(): FakePresignedStorage = FakePresignedStorage() }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mails: Mails
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var purge: AccountPurgeService

    private fun post(path: String, body: String, bearer: String? = null, vararg headers: Pair<String, String>) =
        mvc.post(path) {
            contentType = MediaType.APPLICATION_JSON; content = body
            bearer?.let { header("Authorization", "Bearer $it") }
            header("User-Agent", userAgent)
            headers.forEach { (k, v) -> header(k, v) }
        }

    /** 이 시험만의 UA · 이름 · 이메일 — 지운 뒤 어느 표에도 남지 않아야 한다 */
    private val tag = System.nanoTime()
    private val userAgent = "JourneyBrowser/9.9-$tag"
    private val displayName = "Journey$tag"

    private fun allColumns(): List<Pair<String, String>> =
        jdbc.sql("select table_name, column_name from information_schema.columns where table_schema = current_schema() and table_name not like 'flyway%'")
            .query { rs, _ -> rs.getString(1) to rs.getString(2) }.list()

    private fun codeOf(kind: MailKind, to: String) = mails.sent.last { it.kind == kind && it.to == to }.vars.getValue("code")
    private fun field(json: String, path: String): String = JsonPath.read<Any>(json, path).toString()

    @Test
    fun `the whole account lifecycle, then the board keeps the post as a deleted user's`() {
        val email = "journey-${System.nanoTime()}@example.com"
        val first = "tangerine-42-moon"
        val second = "a-brand-new-pass-7"

        val signUp = post("/api/v1/account/sign-up", """{"email":"$email","password":"$first","displayName":"$displayName","locale":"ko","consents":[{"type":"terms","version":"sample-1"},{"type":"privacy","version":"sample-1"}]}""").andExpect { status { isAccepted() } }.andReturn().response.contentAsString
        // no account exists until the code is entered
        post("/api/v1/auth/login", """{"email":"$email","password":"$first"}""").andExpect { status { isUnauthorized() }; jsonPath("$.code") { value("AUTH.INVALID_CREDENTIALS") } }
        val verified = post("/api/v1/auth/verify-email", """{"signUpId":"${field(signUp, "$.value.signUpId")}","code":"${codeOf(MailKind.VERIFY_CODE, email)}"}""").andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertTrue(field(verified, "$.value.accessToken").isNotBlank() && field(verified, "$.value.refreshToken").startsWith("r1."), "the verifying browser is signed in at once")

        val login = post("/api/v1/auth/login", """{"email":"$email","password":"$first"}""").andExpect { status { isOk() } }.andReturn().response.contentAsString
        val bearer = field(login, "$.value.accessToken")
        val refresh1 = field(login, "$.value.refreshToken")
        val accountId = field(login, "$.value.principal.accountId")

        val rotated = post("/api/v1/auth/refresh", """{"refreshToken":"$refresh1"}""").andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertNotEquals(refresh1, field(rotated, "$.value.refreshToken"))
        post("/api/v1/auth/refresh", """{"refreshToken":"$refresh1"}""").andExpect { status { isUnauthorized() }; jsonPath("$.code") { value("AUTH.REFRESH_REUSED") } }
        post("/api/v1/auth/refresh", """{"refreshToken":"${field(rotated, "$.value.refreshToken")}"}""").andExpect { status { isUnauthorized() } }   // reuse closed the family

        // a post that must survive the deletion
        val relogin = post("/api/v1/auth/login", """{"email":"$email","password":"$first"}""").andReturn().response.contentAsString
        val me = field(relogin, "$.value.accessToken")
        val postId = JsonPath.read<Number>(
            post("/api/v1/boards/general/posts", """{"title":"남길 글","body":"본문"}""", me, "Idempotency-Key" to UUID.randomUUID().toString())
                .andExpect { status { isCreated() } }.andReturn().response.contentAsString, "$.value.id",
        ).toLong()

        post("/api/v1/account/password/change", """{"currentPassword":"$first","newPassword":"$second"}""", me).andExpect { status { isNoContent() } }
        mvc.get("/api/v1/auth/sessions") { header("Authorization", "Bearer $me") }.andExpect { status { isOk() }; jsonPath("$.values[?(@.current==true)]") { exists() } }
        assertTrue(bearer.isNotBlank())
        mails.sent.first { it.kind == MailKind.PASSWORD_CHANGED && it.to == email }

        val auth = field(post("/api/v1/auth/login", """{"email":"$email","password":"$second"}""").andReturn().response.contentAsString, "$.value.accessToken")
        post("/api/v1/account/delete", """{"currentPassword":"$second"}""", auth, "Idempotency-Key" to UUID.randomUUID().toString())
            .andExpect { status { isAccepted() }; jsonPath("$.value.status") { value("DELETION_SCHEDULED") } }
        post("/api/v1/auth/login", """{"email":"$email","password":"$second"}""").andExpect { status { isUnauthorized() } }

        // the grace period passes (we move the clock by rewriting purge_after), the purge runs every erasure listener
        jdbc.sql("update accounts set purge_after = now() - interval '1 minute' where id = :id").param("id", accountId).update()
        assertEquals(1, purge.purgeDue())
        // the row stays (so nothing that points at the account dangles) but is ERASED and holds nothing personal
        val kept = jdbc.sql("select status, email, display_name, locale, time_zone, last_login_at, erased_at from accounts where id = :id").param("id", accountId).query { rs, _ ->
            listOf(rs.getString("status"), rs.getString("email"), rs.getString("display_name"), rs.getString("locale"), rs.getString("time_zone"), rs.getObject("last_login_at"), rs.getObject("erased_at") != null)
        }.single()
        assertEquals(listOf("ERASED", null, null, null, null, null, true), kept)
        assertEquals(0, jdbc.sql("select count(*) from account_identities where account_id = :id").param("id", accountId).query(Long::class.java).single())
        assertEquals(0, jdbc.sql("select count(*) from account_roles where account_id = :id").param("id", accountId).query(Long::class.java).single())
        assertEquals(0, jdbc.sql("select count(*) from auth_sessions where account_id = :id").param("id", accountId).query(Long::class.java).single())
        post("/api/v1/auth/login", """{"email":"$email","password":"$second"}""").andExpect { status { isUnauthorized() } }

        // the one-way guarantee across EVERY table of EVERY module in this app (accounts, sessions, consents, board, notifications, jobs, ...)
        val leaks = allColumns().flatMap { (table, column) ->
            listOf(email, displayName, userAgent).filter { value ->
                jdbc.sql("select count(*) from \"$table\" where cast(\"$column\" as text) like :v").param("v", "%$value%").query(Long::class.java).single() > 0
            }.map { "$table.$column ~ $it" }
        }
        assertEquals(emptyList(), leaks, "no personal value survives the erasure anywhere")

        val admin = field(post("/api/v1/auth/login", """{"email":"admin@example.com","password":"password"}""").andReturn().response.contentAsString, "$.value.accessToken")
        mvc.get("/api/v1/boards/general/posts/$postId") { header("Authorization", "Bearer $admin") }.andExpect {
            status { isOk() }
            jsonPath("$.value.authorDeleted") { value(true) }
            jsonPath("$.value.title") { value("남길 글") }
        }
    }
}
