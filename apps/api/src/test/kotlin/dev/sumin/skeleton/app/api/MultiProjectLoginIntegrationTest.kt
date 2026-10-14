package dev.sumin.skeleton.app.api

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.account.mail.AccountMail
import dev.sumin.skeleton.account.mail.AccountMailer
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * 프로젝트 둘(`a.localhost` · `b.localhost`)이 한 프로세스에서 가입 · 로그인을 따로 한다: 각자 스키마에 계정이 생기고,
 * A 의 계정으로 B 에 로그인할 수 없고, A 의 토큰은 B 에서 거절되고(키가 같아도), 시드 계정도 스키마마다 다르다.
 */
@SpringBootTest(
    properties = [
        "skeleton.projects.a.hosts=a.localhost", "skeleton.projects.a.schema=mpl_a", "skeleton.projects.a.datasource.username=mpl_a", "skeleton.projects.a.datasource.password=mpl_a-pw",
        "skeleton.projects.b.hosts=b.localhost", "skeleton.projects.b.schema=mpl_b", "skeleton.projects.b.datasource.username=mpl_b", "skeleton.projects.b.datasource.password=mpl_b-pw",
        "skeleton.projects.old.hosts=old.localhost", "skeleton.projects.old.status=closed",
        "skeleton.projects.a.config.account.seed.accounts[0].email=seed-a@example.com",
        "skeleton.projects.a.config.account.seed.accounts[0].password=password",
        "skeleton.projects.b.config.account.seed.accounts[0].email=seed-b@example.com",
        "skeleton.projects.b.config.account.seed.accounts[0].password=password",
        "skeleton.projects.a.config.auth.jwt.issuer=issuer-a", "skeleton.projects.b.config.auth.jwt.issuer=issuer-b",
        "skeleton.account.password.bcrypt-strength=4", "skeleton.auth-session.reuse-grace=0s",
        "skeleton.projects.a.config.account.mail.link-base-url=https://a.example.com",
        "skeleton.projects.b.config.account.mail.link-base-url=https://b.example.com",
        "skeleton.projects.a.config.account.mail.brand.service-name=Brand-A",
    ],
)
@AutoConfigureMockMvc
@Import(MultiProjectLoginIntegrationTest.Db::class, MultiProjectLoginIntegrationTest.Mails::class)
class MultiProjectLoginIntegrationTest {
    @TestConfiguration(proxyBeanMethods = false)
    class Db {
        @Bean fun jdbcConnectionDetails(): JdbcConnectionDetails = db
    }

    companion object {
        val db: JdbcConnectionDetails by lazy { MultiProjectTestAccounts.newDatabase("mpl_a", "mpl_b") }
    }
    @TestConfiguration(proxyBeanMethods = false)
    class Mails {
        val sent = CopyOnWriteArrayList<AccountMail>()
        @Bean fun recordingMailer(): AccountMailer = AccountMailer { sent += it }
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mails: Mails

    private fun post(host: String, path: String, body: String, bearer: String? = null): ResultActionsDsl =
        mvc.post(path) {
            with { it.serverName = host; it }
            contentType = MediaType.APPLICATION_JSON; content = body
            bearer?.let { header("Authorization", "Bearer $it") }
        }

    private fun login(host: String, email: String, password: String = "password") = post(host, "/api/v1/auth/login", """{"email":"$email","password":"$password"}""")

    @Test
    fun `sign-up and login are separate per project - the account exists only in the schema of the domain it was made on`() {
        val email = "journey-${System.nanoTime()}@example.com"
        val signUp = post("a.localhost", "/api/v1/account/sign-up", """{"email":"$email","password":"tangerine-42-moon","consents":[{"type":"terms","version":"template-1"},{"type":"privacy","version":"template-1"}]}""")
            .andExpect { status { isAccepted() } }.andReturn().response.contentAsString
        // 진짜 일꾼(account-task 풀)이 메일을 보낸다 — 풀 스레드에도 프로젝트가 따라가야 코드 메일이 나간다
        val deadline = System.currentTimeMillis() + 10_000
        while (mails.sent.none { it.kind.name == "VERIFY_CODE" && it.to == email } && System.currentTimeMillis() < deadline) Thread.sleep(50)
        val code = mails.sent.last { it.kind.name == "VERIFY_CODE" && it.to == email }.vars.getValue("code")
        post("a.localhost", "/api/v1/auth/verify-email", """{"signUpId":"${JsonPath.read<String>(signUp, "$.value.signUpId")}","code":"$code"}""").andExpect { status { isOk() } }

        login("a.localhost", email, "tangerine-42-moon").andExpect { status { isOk() } }
        login("b.localhost", email, "tangerine-42-moon").andExpect { status { isUnauthorized() } }
        // 같은 이메일이 B 에서 따로 가입된다 (B 에는 아직 없는 계정이다)
        post("b.localhost", "/api/v1/account/sign-up", """{"email":"$email","password":"another-pass-77-sun","consents":[{"type":"terms","version":"template-1"},{"type":"privacy","version":"template-1"}]}""")
            .andExpect { status { isAccepted() } }
        login("b.localhost", email, "another-pass-77-sun").andExpect { status { isUnauthorized() } }   // 확인 코드 전에는 계정이 없다
    }

    @Test
    fun `seed accounts are per project - A's seed does not exist in B`() {
        login("a.localhost", "seed-a@example.com").andExpect { status { isOk() } }
        login("b.localhost", "seed-a@example.com").andExpect { status { isUnauthorized() } }
        login("b.localhost", "seed-b@example.com").andExpect { status { isOk() } }
        login("a.localhost", "seed-b@example.com").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `a token from A is rejected in B`() {
        val token = JsonPath.read<String>(login("a.localhost", "seed-a@example.com").andReturn().response.contentAsString, "$.value.accessToken")
        mvc.get("/api/v1/auth/me") { with { it.serverName = "a.localhost"; it }; header("Authorization", "Bearer $token") }.andExpect { status { isOk() } }
        mvc.get("/api/v1/auth/me") { with { it.serverName = "b.localhost"; it }; header("Authorization", "Bearer $token") }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `an unknown domain is refused, a closed project answers 503 and the health probe passes`() {
        mvc.get("/api/v1/auth/me") { with { it.serverName = "evil.example"; it } }.andExpect { status { isMisdirectedRequest() } }
        mvc.get("/api/v1/auth/me") { with { it.serverName = "old.localhost"; it }; }.andExpect { status { isServiceUnavailable() } }
        val health = mvc.get("/health") { with { it.serverName = "localhost"; it } }.andReturn().response
        assertEquals(200, health.status, health.contentAsString)
        assertTrue(health.contentAsString.contains("UP"))
    }

    @Test
    fun `the mail links and brand come from the project of the request`() {
        val email = "mail-${System.nanoTime()}@example.com"
        post("a.localhost", "/api/v1/account/password/forgot", """{"email":"$email"}""")
        // 가입하지 않은 이메일이라 메일이 안 갈 수 있다 — 링크 주소가 필요한 흐름은 아래 시드 계정으로
        post("b.localhost", "/api/v1/account/password/forgot", """{"email":"seed-b@example.com"}""")
        val deadline = System.currentTimeMillis() + 10_000
        while (mails.sent.none { it.to == "seed-b@example.com" } && System.currentTimeMillis() < deadline) Thread.sleep(50)
        val mailToB = mails.sent.lastOrNull { it.to == "seed-b@example.com" }
        assertTrue(mailToB?.link?.startsWith("https://b.example.com") == true, "link: ${mailToB?.link}")
    }
}
