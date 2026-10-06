package dev.sumin.skeleton.app.api

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.account.abuse.AccountTaskRunner
import dev.sumin.skeleton.account.mail.AccountMail
import dev.sumin.skeleton.account.mail.AccountMailer
import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployEnv
import dev.sumin.skeleton.common.deploy.DeployGuard
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
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
 * 스타터가 "켜기만 해도 서비스 구색" 을 갖춘다는 증거 — 약관 · 개인정보 처리방침(모듈의 TEMPLATE 문서)이 공개로 서고, 가입은 동의 없이는 거절되며,
 * 동의는 이메일 확인 때 계정과 함께 기록되고, 이 문서를 그대로 단 채 prod 로 뜨는 것은 가드가 막는다. 재동의 필터는 스타터 설정으로 켜져 있다.
 */
@SpringBootTest(properties = ["spring.config.import=classpath:test-seeds.yml", "skeleton.account.password.bcrypt-strength=4", "skeleton.account.mail.link-base-url=https://app.example.com"])
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, LegalStarterIntegrationTest.Mails::class)
class LegalStarterIntegrationTest {
    @TestConfiguration(proxyBeanMethods = false)
    class Mails {
        val sent = CopyOnWriteArrayList<AccountMail>()
        @Bean fun recordingMailer(): AccountMailer = AccountMailer { sent += it }
        @Bean fun directTasks(): AccountTaskRunner = AccountTaskRunner.DIRECT
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mails: Mails
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var guards: List<DeployGuard>

    private fun post(path: String, body: String, bearer: String? = null) = mvc.post(path) {
        contentType = MediaType.APPLICATION_JSON; content = body
        bearer?.let { header("Authorization", "Bearer $it") }
    }

    @Test
    fun `the documents are public and are the template text until the app brings its own`() {
        mvc.get("/api/v1/legal/documents").andExpect {
            status { isOk() }
            jsonPath("$.values[?(@.type=='terms' && @.locale=='ko')].template") { value(true) }
            jsonPath("$.values[?(@.type=='privacy' && @.locale=='en')].requiredAtSignUp") { value(true) }
            jsonPath("$.values[?(@.type=='marketing' && @.locale=='ko')].requiredAtSignUp") { value(false) }
        }
        mvc.get("/api/v1/legal/documents/terms").andExpect { status { isOk() }; jsonPath("$.value.markdown") { value(org.hamcrest.Matchers.containsString("TEMPLATE")) } }
    }

    @Test
    fun `a sign-up without the required consents is refused, with them it is recorded when the code is verified, and the account works`() {
        val email = "legal-${System.nanoTime()}@example.com"
        val body = { consents: String -> """{"email":"$email","password":"tangerine-42-moon","consents":$consents}""" }

        post("/api/v1/account/sign-up", body("""[{"type":"terms","version":"template-1"}]""")).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("LEGAL.CONSENT_REQUIRED") }
            jsonPath("$.data.missing[0].type") { value("privacy") }
            jsonPath("$.data.missing[0].reason") { value("NOT_AGREED") }
        }
        assertEquals(0, mails.sent.size)

        val signUp = post("/api/v1/account/sign-up", body("""[{"type":"terms","version":"template-1","locale":"ko"},{"type":"privacy","version":"template-1"},{"type":"marketing","version":"template-1"}]"""))
            .andExpect { status { isAccepted() } }.andReturn().response.contentAsString
        assertEquals(0, jdbc.sql("select count(*) from legal_consents").query(Int::class.java).single(), "nothing is recorded before the code is verified")

        val code = mails.sent.last { it.kind.name == "VERIFY_CODE" && it.to == email }.vars.getValue("code")
        val verified = post("/api/v1/auth/verify-email", """{"signUpId":"${JsonPath.read<String>(signUp, "$.value.signUpId")}","code":"$code"}""")
            .andExpect { status { isOk() } }.andReturn().response.contentAsString
        val bearer = JsonPath.read<String>(verified, "$.value.accessToken")
        val accountId = JsonPath.read<String>(verified, "$.value.principal.accountId")

        val rows = jdbc.sql("select document_type, version, source, action from legal_consents where subject_id = :id order by id").param("id", accountId)
            .query { rs, _ -> listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)) }.list()
        assertEquals(listOf(listOf("terms", "template-1", "sign-up", "AGREED"), listOf("privacy", "template-1", "sign-up", "AGREED"), listOf("marketing", "template-1", "sign-up", "AGREED")), rows)

        mvc.get("/api/v1/legal/consents/me") { header("Authorization", "Bearer $bearer") }.andExpect {
            status { isOk() }
            jsonPath("$.value.blocked") { value(false) }
            jsonPath("$.value.items[?(@.type=='terms')].state") { value("CURRENT") }
        }
        mvc.get("/api/v1/hello") { header("Authorization", "Bearer $bearer") }.andExpect { status { isOk() } }
    }

    @Test
    fun `a signed-in account without consent is told to consent before it can use the api, and the way to fix it is open`() {
        // seeded accounts never went through the sign-up form, so they have no consent (the same shape as a first social or magic-link sign-in)
        val login = mvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON; content = """{"email":"user@example.com","password":"password"}"""
        }.andReturn().response.contentAsString
        val bearer = JsonPath.read<String>(login, "$.value.accessToken")

        mvc.get("/api/v1/hello") { header("Authorization", "Bearer $bearer") }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("LEGAL.RECONSENT_REQUIRED") }
            jsonPath("$.data.missing[0].type") { value("terms") }
        }
        post("/api/v1/legal/consents", """{"consents":[{"type":"terms","version":"template-1"},{"type":"privacy","version":"template-1"}],"source":"first-sign-in"}""", bearer)
            .andExpect { status { isOk() }; jsonPath("$.value.blocked") { value(false) } }
        mvc.get("/api/v1/hello") { header("Authorization", "Bearer $bearer") }.andExpect { status { isOk() } }
    }

    @Test
    fun `prod refuses to start on the template documents unless the operator acknowledged them`() {
        val legal = guards.single { it.name == "legal" }
        val problems = legal.problems(DeployContext(DeployEnv.PROD, emptySet()))
        assertTrue(problems.single().contains("skeleton.legal.acknowledge-template"), problems.toString())
        assertEquals(emptyList(), legal.problems(DeployContext(DeployEnv.LOCAL, emptySet())))
    }
}
