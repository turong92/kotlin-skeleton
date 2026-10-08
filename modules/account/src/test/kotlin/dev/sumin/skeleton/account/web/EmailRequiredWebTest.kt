package dev.sumin.skeleton.account.web

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.account.RecordingMailer
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.accounttest.AccountTestBeans
import dev.sumin.skeleton.accounttest.AccountWebTestApplication
import dev.sumin.skeleton.accounttest.FakeSocialBeans
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

/** 다른 모듈의 엔드포인트를 흉내 내는 보호된 경로 — board · notes 처럼 account 를 모르는 컨트롤러 */
@RestController
class ForeignModuleController {
    @GetMapping("/api/v1/things") fun things() = mapOf("ok" to true)
    @PostMapping("/api/v1/things") fun create() = mapOf("ok" to true)
}

@TestConfiguration(proxyBeanMethods = false)
class ForeignModuleBeans {
    @Bean fun foreignModuleController() = ForeignModuleController()
}

/** `skeleton.account.email.required=true` — 주소 없는 계정은 허용 목록 밖의 모든 API 에서 403 `ACCOUNT.EMAIL_REQUIRED`, 첫 주소는 재인증 없이 등록 */
@SpringBootTest(
    classes = [AccountWebTestApplication::class],
    properties = [
        "skeleton.account.mail.link-base-url=https://app.example.com",
        "skeleton.account.password.bcrypt-strength=4",
        "skeleton.account.social.sign-up=true",
        "skeleton.account.email.required=true",
        "skeleton.auth-social.providers.fakeidp.enabled=true",
    ],
)
@AutoConfigureMockMvc
@Import(AccountTestBeans::class, FakeSocialBeans::class, ForeignModuleBeans::class)
class EmailRequiredWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mail: RecordingMailer

    private val json = MediaType.APPLICATION_JSON
    private fun bearer(r: MvcResult) = "Bearer " + JsonPath.read<String>(r.response.contentAsString, "$.value.accessToken")
    private fun social(code: String) = mvc.perform(post("/api/v1/auth/social/fakeidp/login").contentType(json).content("""{"authorizationCode":"$code"}""")).andExpect(status().isOk).andReturn()

    private fun passwordAccount(email: String): String {
        mail.sent.clear()
        val signUp = mvc.perform(post("/api/v1/account/sign-up").contentType(json).content("""{"email":"$email","password":"tangerine-42-moon"}""")).andExpect(status().isAccepted).andReturn()
        val id = JsonPath.read<String>(signUp.response.contentAsString, "$.value.signUpId")
        val verified = mvc.perform(post("/api/v1/auth/verify-email").contentType(json).content("""{"signUpId":"$id","code":"${mail.of(MailKind.VERIFY_CODE).last().vars.getValue("code")}"}""")).andExpect(status().isOk).andReturn()
        return bearer(verified)
    }

    private fun changeEmail(auth: String, to: String) = mvc.perform(post("/api/v1/account/email/change").header("Authorization", auth).header("Idempotency-Key", java.util.UUID.randomUUID().toString()).contentType(json).content("""{"newEmail":"$to"}"""))

    @Test
    fun `an account without an address is stopped on every endpoint outside the allow list - also on other modules' endpoints`() {
        val auth = bearer(social("c-noemail-req"))

        mvc.perform(get("/api/v1/things").header("Authorization", auth)).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("ACCOUNT.EMAIL_REQUIRED"))
        mvc.perform(post("/api/v1/things").header("Authorization", auth)).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("ACCOUNT.EMAIL_REQUIRED"))
        mvc.perform(patch("/api/v1/account/me").header("Authorization", auth).contentType(json).content("""{"locale":"ko"}""")).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("ACCOUNT.EMAIL_REQUIRED"))
        mvc.perform(get("/api/v1/account/identities").header("Authorization", auth)).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("ACCOUNT.EMAIL_REQUIRED"))
        mvc.perform(delete("/api/v1/account/identities/idn_x").header("Authorization", auth)).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("ACCOUNT.EMAIL_REQUIRED"))
    }

    @Test
    fun `the allow list stays open - me, the email flow, sign-out and the auth endpoints, deletion`() {
        val auth = bearer(social("c-noemail-req"))

        mvc.perform(get("/api/v1/account/me").header("Authorization", auth)).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.emailRequired").value(true))
        mvc.perform(get("/api/v1/auth/me").header("Authorization", auth)).andExpect(status().isOk)
        mvc.perform(get("/api/v1/auth/methods")).andExpect(status().isOk)
        mvc.perform(post("/api/v1/account/delete/confirmation").header("Authorization", auth)).andExpect(status().isAccepted)
    }

    @Test
    fun `the first address is registered without re-authentication, then the account is usable`() {
        val auth = bearer(social("c-noemail-req2"))
        mail.sent.clear()

        changeEmail(auth, "Kim@Example.com").andExpect(status().isAccepted)
            .andExpect(jsonPath("$.value.status").value("VERIFICATION_SENT"))
            .andExpect(jsonPath("$.value.expiresAt").isNotEmpty)
            .andExpect(jsonPath("$.value.resendAvailableAt").isNotEmpty)
        val sent = mail.of(MailKind.EMAIL_CHANGE_CODE).single()
        assertEquals("kim@example.com", sent.to)
        mvc.perform(get("/api/v1/things").header("Authorization", auth)).andExpect(status().isForbidden)   // not yet - the code was not entered

        mvc.perform(post("/api/v1/account/email/change/confirm").header("Authorization", auth).contentType(json).content("""{"code":"${sent.vars.getValue("code")}"}"""))
            .andExpect(status().isNoContent)

        mvc.perform(get("/api/v1/things").header("Authorization", auth)).andExpect(status().isOk)
        mvc.perform(get("/api/v1/account/me").header("Authorization", auth)).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.email").value("kim@example.com"))
            .andExpect(jsonPath("$.value.emailVerified").value(true))
            .andExpect(jsonPath("$.value.emailRequired").value(false))
    }

    @Test
    fun `an address that already has an account answers the same 202 and mails the owner - no code, and the screen cannot tell`() {
        passwordAccount("owner@example.com")
        val auth = bearer(social("c-noemail-req3"))
        mail.sent.clear()

        val taken = changeEmail(auth, "owner@example.com").andExpect(status().isAccepted).andReturn()
        assertEquals("owner@example.com", mail.of(MailKind.ALREADY_REGISTERED).single().to)
        assertTrue(mail.of(MailKind.EMAIL_CHANGE_CODE).isEmpty(), "no code to the owner's address")
        val free = changeEmail(auth, "free@example.com").andExpect(status().isAccepted).andReturn()

        fun shape(r: MvcResult) = JsonPath.read<Map<String, Any?>>(r.response.contentAsString, "$.value").keys
        assertEquals(shape(free), shape(taken))
        mvc.perform(post("/api/v1/account/email/change/confirm").header("Authorization", auth).contentType(json).content("""{"code":"000000"}"""))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ACCOUNT.CODE_INVALID"))
    }

    @Test
    fun `an account that has an address is untouched - the endpoints work and me says no address is needed`() {
        val auth = passwordAccount("plain@example.com")
        mvc.perform(get("/api/v1/things").header("Authorization", auth)).andExpect(status().isOk)
        mvc.perform(get("/api/v1/account/me").header("Authorization", auth)).andExpect(jsonPath("$.value.emailRequired").value(false))
    }

    @Test
    fun `an anonymous call keeps its 401 - the filter only speaks to signed-in callers`() {
        mvc.perform(get("/api/v1/things")).andExpect(status().isUnauthorized)
    }
}
