package dev.sumin.skeleton.account.web

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.account.RecordingMailer
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.accounttest.AccountTestBeans
import dev.sumin.skeleton.accounttest.AccountWebTestApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** `skeleton.account.contact-email.enabled=true` — 등록 요청 → 코드 → 확인, `me.contactEmail`, 삭제 */
@SpringBootTest(
    classes = [AccountWebTestApplication::class],
    properties = [
        "skeleton.account.mail.link-base-url=https://app.example.com", "skeleton.account.password.bcrypt-strength=4",
        "skeleton.account.contact-email.enabled=true",
    ],
)
@AutoConfigureMockMvc
@Import(AccountTestBeans::class)
class ContactEmailWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mail: RecordingMailer

    private val json = MediaType.APPLICATION_JSON

    private fun signedIn(email: String): String {
        mail.sent.clear()
        val signUp = mvc.perform(post("/api/v1/account/sign-up").contentType(json).content("""{"email":"$email","password":"tangerine-42-moon"}""")).andExpect(status().isAccepted).andReturn()
        val id = JsonPath.read<String>(signUp.response.contentAsString, "$.value.signUpId")
        val r = mvc.perform(post("/api/v1/auth/verify-email").contentType(json).content("""{"signUpId":"$id","code":"${mail.of(MailKind.VERIFY_CODE).last().vars.getValue("code")}"}""")).andExpect(status().isOk).andReturn()
        return "Bearer " + JsonPath.read<String>(r.response.contentAsString, "$.value.accessToken")
    }

    @Test
    fun `register, confirm, read it from me, remove it`() {
        val auth = signedIn("web-contact@example.com")
        mvc.perform(get("/api/v1/account/me").header("Authorization", auth)).andExpect(jsonPath("$.value.contactEmail").value(null as String?))

        mail.sent.clear()
        mvc.perform(post("/api/v1/account/contact-email").header("Authorization", auth).contentType(json).content("""{"email":"Reach@Example.com"}"""))
            .andExpect(status().isAccepted).andExpect(jsonPath("$.value.status").value("VERIFICATION_SENT"))
            .andExpect(jsonPath("$.value.expiresAt").isNotEmpty).andExpect(jsonPath("$.value.resendAvailableAt").isNotEmpty)
        val sent = mail.of(MailKind.CONTACT_EMAIL_CODE).single()
        assertEquals("reach@example.com", sent.to)

        mvc.perform(post("/api/v1/account/contact-email/confirm").header("Authorization", auth).contentType(json).content("""{"code":"000000"}"""))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ACCOUNT.CODE_INVALID"))
        mvc.perform(post("/api/v1/account/contact-email/confirm").header("Authorization", auth).contentType(json).content("""{"code":"${sent.vars.getValue("code")}"}""")).andExpect(status().isNoContent)

        mvc.perform(get("/api/v1/account/me").header("Authorization", auth))
            .andExpect(jsonPath("$.value.contactEmail").value("reach@example.com")).andExpect(jsonPath("$.value.email").value("web-contact@example.com"))
        assertEquals("web-contact@example.com", mail.of(MailKind.CONTACT_EMAIL_NOTICE).single().to)

        mvc.perform(delete("/api/v1/account/contact-email").header("Authorization", auth)).andExpect(status().isNoContent)
        mvc.perform(get("/api/v1/account/me").header("Authorization", auth)).andExpect(jsonPath("$.value.contactEmail").value(null as String?))
    }

    @Test
    fun `an address that is shaped wrong is a validation error, anonymous is 401`() {
        val auth = signedIn("web-contact2@example.com")
        mvc.perform(post("/api/v1/account/contact-email").header("Authorization", auth).contentType(json).content("""{"email":"nope"}""")).andExpect(status().isBadRequest)
        mvc.perform(post("/api/v1/account/contact-email").contentType(json).content("""{"email":"a@example.com"}""")).andExpect(status().isUnauthorized)
    }
}
