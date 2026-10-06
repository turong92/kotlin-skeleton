package dev.sumin.skeleton.app.api

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.account.abuse.AccountTaskRunner
import dev.sumin.skeleton.account.mail.AccountMail
import dev.sumin.skeleton.account.mail.AccountMailer
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/** 스타터의 yml 이 정한 기본(`reuse-grace: 10s`)으로 — 응답을 잃은 refresh 의 재시도가 세션을 죽이지 않는다 */
@SpringBootTest(properties = ["skeleton.account.password.bcrypt-strength=4", "skeleton.account.mail.link-base-url=https://app.example.com"])
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, RefreshGraceIntegrationTest.Mails::class)
class RefreshGraceIntegrationTest {
    @TestConfiguration(proxyBeanMethods = false)
    class Mails {
        val sent = CopyOnWriteArrayList<AccountMail>()
        @Bean fun recordingMailer(): AccountMailer = AccountMailer { sent += it }
        @Bean fun directTasks(): AccountTaskRunner = AccountTaskRunner.DIRECT
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mails: Mails

    private fun json(path: String, body: String) = mvc.post(path) { contentType = MediaType.APPLICATION_JSON; content = body }

    @Test
    fun `a refresh whose response was lost is retried with the same token and gets the same successor`() {
        val email = "lost-${System.nanoTime()}@example.com"
        val signUp = json("/api/v1/account/sign-up", """{"email":"$email","password":"tangerine-42-moon"}""").andReturn().response.contentAsString
        json("/api/v1/auth/verify-email", """{"signUpId":"${JsonPath.read<String>(signUp, "$.value.signUpId")}","code":"${mails.sent.last { it.kind.name == "VERIFY_CODE" }.vars.getValue("code")}"}""")
        val first = JsonPath.read<String>(json("/api/v1/auth/login", """{"email":"$email","password":"tangerine-42-moon"}""").andReturn().response.contentAsString, "$.value.refreshToken")
        val lost = JsonPath.read<String>(json("/api/v1/auth/refresh", """{"refreshToken":"$first"}""").andExpect { status { isOk() } }.andReturn().response.contentAsString, "$.value.refreshToken")
        val retry = JsonPath.read<String>(json("/api/v1/auth/refresh", """{"refreshToken":"$first"}""").andExpect { status { isOk() } }.andReturn().response.contentAsString, "$.value.refreshToken")
        assertEquals(lost, retry)
        json("/api/v1/auth/refresh", """{"refreshToken":"$retry"}""").andExpect { status { isOk() } }
    }

    @Test
    fun `the public methods listing tells the frontend how refresh tokens travel and what sign-in is open`() {
        mvc.get("/api/v1/auth/methods").andExpect {
            status { isOk() }
            jsonPath("$.value.refreshDelivery") { value("body") }
            jsonPath("$.value.methods[0]") { value("password") }
            jsonPath("$.value.signUp.password") { value(true) }
        }
    }
}
