package dev.sumin.skeleton.account.web

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.account.RecordingMailer
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.accounttest.AccountTestBeans
import dev.sumin.skeleton.accounttest.AccountWebTestApplication
import kotlin.test.Test
import kotlin.test.assertFalse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** `skeleton.account.contact-email.enabled` 의 기본(false) — 엔드포인트가 아예 없고(404) `me` 에 `contactEmail` 키도 없다 */
@SpringBootTest(classes = [AccountWebTestApplication::class], properties = ["skeleton.account.mail.link-base-url=https://app.example.com", "skeleton.account.password.bcrypt-strength=4"])
@AutoConfigureMockMvc
@Import(AccountTestBeans::class)
class ContactEmailDisabledWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mail: RecordingMailer
    private val json = MediaType.APPLICATION_JSON

    @Test
    fun `with the feature off the contact-email endpoints are 404 and me has no contactEmail key`() {
        val signUp = mvc.perform(post("/api/v1/account/sign-up").contentType(json).content("""{"email":"off@example.com","password":"tangerine-42-moon"}""")).andExpect(status().isAccepted).andReturn()
        val id = JsonPath.read<String>(signUp.response.contentAsString, "$.value.signUpId")
        val r = mvc.perform(post("/api/v1/auth/verify-email").contentType(json).content("""{"signUpId":"$id","code":"${mail.of(MailKind.VERIFY_CODE).last().vars.getValue("code")}"}""")).andExpect(status().isOk).andReturn()
        val auth = "Bearer " + JsonPath.read<String>(r.response.contentAsString, "$.value.accessToken")

        mvc.perform(post("/api/v1/account/contact-email").header("Authorization", auth).contentType(json).content("""{"email":"c@example.com"}""")).andExpect(status().isNotFound)
        mvc.perform(post("/api/v1/account/contact-email/confirm").header("Authorization", auth).contentType(json).content("""{"code":"000000"}""")).andExpect(status().isNotFound)
        mvc.perform(delete("/api/v1/account/contact-email").header("Authorization", auth)).andExpect(status().isNotFound)
        val me = mvc.perform(get("/api/v1/account/me").header("Authorization", auth)).andExpect(status().isOk).andReturn()
        assertFalse("contactEmail" in JsonPath.read<Map<String, Any?>>(me.response.contentAsString, "$.value"))
    }
}
