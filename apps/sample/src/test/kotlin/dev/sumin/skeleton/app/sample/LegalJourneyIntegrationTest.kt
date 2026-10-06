package dev.sumin.skeleton.app.sample

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.account.abuse.AccountTaskRunner
import dev.sumin.skeleton.account.mail.AccountMail
import dev.sumin.skeleton.account.mail.AccountMailer
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
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
 * 약관 · 동의의 한 바퀴를 진짜 PostgreSQL · 보안 체인 · 모듈들과 함께: 동의를 달아 가입 → 이메일 확인에서 기록 → 새 판 시행 → (유예 안) 통과 → (유예 밖) 403 재동의 →
 * 동의하면 통과 → 마케팅 철회 · 다시 동의. 시계는 고정 · 이동하는 가짜(`legal-journey/` 문서는 terms v2 가 2026-10-08 에 시행).
 */
@SpringBootTest(
    properties = [
        "skeleton.account.mail.link-base-url=https://app.example.com", "skeleton.account.password.bcrypt-strength=4",
        "skeleton.legal.location=classpath:legal-journey/", "skeleton.legal.previous-version-grace=1h", "skeleton.legal.reconsent.enabled=true",
        "skeleton.legal.facts.company-name=Notes", "skeleton.legal.facts.contact-email=a@b.c",
    ],
)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, LegalJourneyIntegrationTest.Fakes::class)
class LegalJourneyIntegrationTest {
    class Clock(@Volatile var now: Instant) : TimeProvider { override fun now(): Instant = now }

    @TestConfiguration(proxyBeanMethods = false)
    class Fakes {
        val sent = CopyOnWriteArrayList<AccountMail>()
        @Bean fun clock() = Clock(Instant.parse("2026-10-07T00:00:00Z"))
        @Bean fun recordingMailer(): AccountMailer = AccountMailer { sent += it }
        @Bean fun directTasks(): AccountTaskRunner = AccountTaskRunner.DIRECT
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var fakes: Fakes
    @Autowired lateinit var clock: Clock
    @Autowired lateinit var jdbc: JdbcClient

    private fun post(path: String, body: String = "{}", bearer: String? = null) = mvc.post(path) {
        contentType = MediaType.APPLICATION_JSON; content = body
        bearer?.let { header("Authorization", "Bearer $it") }
    }

    private fun get(path: String, bearer: String) = mvc.get(path) { header("Authorization", "Bearer $bearer") }

    @Test
    fun `sign up with consents, a new version takes effect, re-consent, then withdraw and agree to marketing`() {
        val email = "legal-${System.nanoTime()}@example.com"

        // 1. 동의 없이는 가입이 거절된다 — 메일은 나가지 않는다
        post("/api/v1/account/sign-up", """{"email":"$email","password":"tangerine-42-moon","consents":[{"type":"terms","version":"v1"}]}""")
            .andExpect { status { isBadRequest() }; jsonPath("$.code") { value("LEGAL.CONSENT_REQUIRED") }; jsonPath("$.data.missing[0].type") { value("privacy") } }
        assertEquals(0, fakes.sent.size)

        // 2. 동의를 달아 가입 → 코드를 입력해야 계정 · 동의 기록이 생긴다
        val signUp = post(
            "/api/v1/account/sign-up",
            """{"email":"$email","password":"tangerine-42-moon","consents":[{"type":"terms","version":"v1","locale":"ko"},{"type":"privacy","version":"v1"},{"type":"marketing","version":"v1"}]}""",
        ).andExpect { status { isAccepted() } }.andReturn().response.contentAsString
        assertEquals(0, jdbc.sql("select count(*) from legal_consents").query(Int::class.java).single())
        val code = fakes.sent.last { it.kind.name == "VERIFY_CODE" && it.to == email }.vars.getValue("code")
        val verified = post("/api/v1/auth/verify-email", """{"signUpId":"${JsonPath.read<String>(signUp, "$.value.signUpId")}","code":"$code"}""")
            .andExpect { status { isOk() } }.andReturn().response.contentAsString
        val accountId = JsonPath.read<String>(verified, "$.value.principal.accountId")
        var bearer = JsonPath.read<String>(verified, "$.value.accessToken")

        val rows = jdbc.sql("select document_type, version, source from legal_consents where subject_id = :id order by id").param("id", accountId)
            .query { rs, _ -> "${rs.getString(1)}:${rs.getString(2)}:${rs.getString(3)}" }.list()
        assertEquals(listOf("terms:v1:sign-up", "privacy:v1:sign-up", "marketing:v1:sign-up"), rows)
        get("/api/v1/notes", bearer).andExpect { status { isOk() } }

        // 3. 새 판(terms v2)이 시행됐지만 유예(1 시간) 안이다 — 통과하고 상태는 GRACE
        clock.now = Instant.parse("2026-10-08T00:30:00Z")
        bearer = JsonPath.read(post("/api/v1/auth/login", """{"email":"$email","password":"tangerine-42-moon"}""").andReturn().response.contentAsString, "$.value.accessToken")
        get("/api/v1/notes", bearer).andExpect { status { isOk() } }
        get("/api/v1/legal/consents/me", bearer).andExpect {
            status { isOk() }
            jsonPath("$.value.blocked") { value(false) }
            jsonPath("$.value.items[?(@.type=='terms')].state") { value("GRACE") }
        }

        // 4. 유예가 끝나면 보호된 호출은 403 — 목록을 주고, 동의 화면 쪽 엔드포인트는 열려 있다
        clock.now = Instant.parse("2026-10-08T02:00:00Z")
        bearer = JsonPath.read(post("/api/v1/auth/login", """{"email":"$email","password":"tangerine-42-moon"}""").andReturn().response.contentAsString, "$.value.accessToken")
        get("/api/v1/notes", bearer).andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("LEGAL.RECONSENT_REQUIRED") }
            jsonPath("$.data.missing[0].type") { value("terms") }
            jsonPath("$.data.missing[0].version") { value("v2") }
            jsonPath("$.data.missing[0].reason") { value("STALE") }
        }
        get("/api/v1/legal/consents/me", bearer).andExpect { status { isOk() }; jsonPath("$.value.blocked") { value(true) } }
        get("/api/v1/legal/documents/terms", bearer).andExpect { status { isOk() }; jsonPath("$.value.version") { value("v2") } }
        post("/api/v1/legal/consents", """{"consents":[{"type":"terms","version":"v1"}]}""", bearer).andExpect { status { isConflict() }; jsonPath("$.code") { value("LEGAL.VERSION_STALE") } }

        // 5. 새 판에 동의하면 다시 쓸 수 있다
        post("/api/v1/legal/consents", """{"consents":[{"type":"terms","version":"v2","locale":"en"}],"source":"re-consent"}""", bearer)
            .andExpect { status { isOk() }; jsonPath("$.value.blocked") { value(false) } }
        get("/api/v1/notes", bearer).andExpect { status { isOk() } }

        // 6. 마케팅 철회는 따로 기록되고 서비스는 그대로 쓴다 · 필수 문서는 거둘 수 없다 · 다시 동의
        post("/api/v1/legal/consents/marketing/withdraw", bearer = bearer).andExpect { status { isOk() }; jsonPath("$.value.items[?(@.type=='marketing')].state") { value("WITHDRAWN") } }
        get("/api/v1/notes", bearer).andExpect { status { isOk() } }
        post("/api/v1/legal/consents/terms/withdraw", bearer = bearer).andExpect { status { isConflict() }; jsonPath("$.code") { value("LEGAL.WITHDRAWAL_NOT_ALLOWED") } }
        post("/api/v1/legal/consents", """{"consents":[{"type":"marketing","version":"v1"}]}""", bearer).andExpect { status { isOk() } }

        val history = jdbc.sql("select document_type, version, action, source, locale from legal_consents where subject_id = :id order by id").param("id", accountId)
            .query { rs, _ -> listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)) }.list()
        assertEquals(
            listOf(
                listOf("terms", "v1", "AGREED", "sign-up", "ko"), listOf("privacy", "v1", "AGREED", "sign-up", "ko"), listOf("marketing", "v1", "AGREED", "sign-up", "ko"),
                listOf("terms", "v2", "AGREED", "re-consent", "en"),
                listOf("marketing", "v1", "WITHDRAWN", "withdraw", "ko"), listOf("marketing", "v1", "AGREED", "consent", "ko"),
            ),
            history,
        )
        get("/api/v1/legal/consents/me/history", bearer).andExpect { status { isOk() }; jsonPath("$.pagination.totalElements") { value(6) }; jsonPath("$.values[0].ip") { doesNotExist() } }
    }
}
