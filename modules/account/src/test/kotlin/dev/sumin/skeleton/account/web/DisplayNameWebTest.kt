package dev.sumin.skeleton.account.web

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.account.RecordingMailer
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.accounttest.AccountTestBeans
import dev.sumin.skeleton.accounttest.AccountWebTestApplication
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

private val ipCounter = AtomicInteger()

/** 닉네임 UNIQUE + 필수 + 예약어 — HTTP 계약 (docs/account-http-contract.md "닉네임") */
@SpringBootTest(
    classes = [AccountWebTestApplication::class],
    properties = [
        "skeleton.account.mail.link-base-url=https://app.example.com",
        "skeleton.account.password.bcrypt-strength=4",
        "skeleton.account.admin.enabled=true",
        "skeleton.account.display-name.uniqueness=UNIQUE",
        "skeleton.account.display-name.required-on-sign-up=true",
        "skeleton.account.display-name.reserved=admin,운영자",
    ],
)
@AutoConfigureMockMvc
@Import(AccountTestBeans::class)
class DisplayNameWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mail: RecordingMailer

    @BeforeEach fun clear() { mail.sent.clear() }

    private fun MockHttpServletRequestBuilder.json(body: String) = contentType(MediaType.APPLICATION_JSON).content(body)
    private fun unique() = "u${System.nanoTime()}@example.com"
    private fun nick() = "n${System.nanoTime()}"
    private fun bearer(r: MvcResult) = "Bearer " + JsonPath.read<String>(r.response.contentAsString, "$.value.accessToken")

    private fun signUp(email: String, name: String?) =
        mvc.perform(
            post("/api/v1/account/sign-up").with { it.remoteAddr = "198.51.100.${100 + ipCounter.getAndIncrement() % 100}"; it }
                .json("""{"email":"$email","password":"tangerine-42-moon"${name?.let { ""","displayName":"$it"""" } ?: ""}}"""),
        )

    private fun verify(signUpResult: org.springframework.test.web.servlet.ResultActions): org.springframework.test.web.servlet.ResultActions {
        val id = JsonPath.read<String>(signUpResult.andReturn().response.contentAsString, "$.value.signUpId")
        val code = mail.of(MailKind.VERIFY_CODE).last().vars.getValue("code")
        return mvc.perform(post("/api/v1/auth/verify-email").json("""{"signUpId":"$id","code":"$code"}"""))
    }

    private fun registered(name: String): String {
        val r = verify(signUp(unique(), name).andExpect(status().isAccepted)).andExpect(status().isOk).andReturn()
        return bearer(r)
    }

    @Test
    fun `a missing required nickname is a 400 on the displayName field, the same for a new and a registered address`() {
        val known = unique()
        verify(signUp(known, nick()).andExpect(status().isAccepted)).andExpect(status().isOk)
        listOf(unique(), known).forEach { email ->
            signUp(email, null).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("COMMON.VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("displayName"))
                .andExpect(jsonPath("$.errors[0].code").value("Required"))
        }
    }

    @Test
    fun `an unfit or reserved nickname is a 400 on the displayName field`() {
        signUp(unique(), "bad#tag").andExpect(status().isBadRequest).andExpect(jsonPath("$.errors[0].field").value("displayName")).andExpect(jsonPath("$.errors[0].code").value("Pattern"))
        signUp(unique(), "ADMIN").andExpect(status().isBadRequest).andExpect(jsonPath("$.errors[0].field").value("displayName")).andExpect(jsonPath("$.errors[0].code").value("Reserved"))
    }

    @Test
    fun `a taken nickname does not show on the 202 and is the 409 when the verification would create the account`() {
        val name = nick()
        registered(name)
        val second = signUp(unique(), name.uppercase()).andExpect(status().isAccepted).andExpect(jsonPath("$.value.status").value("VERIFICATION_SENT"))
        verify(second).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("ACCOUNT.DISPLAY_NAME_TAKEN"))
    }

    @Test
    fun `profile update onto a taken nickname is the 409 and the update changes nothing, me carries displayTag null in UNIQUE`() {
        val takenName = nick()
        registered(takenName)
        val auth = registered(nick())
        mvc.perform(patch("/api/v1/account/me").header("Authorization", auth).json("""{"displayName":"$takenName","locale":"en"}"""))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("ACCOUNT.DISPLAY_NAME_TAKEN"))
        val me = mvc.perform(get("/api/v1/account/me").header("Authorization", auth)).andExpect(status().isOk).andExpect(jsonPath("$.value.displayName").isString).andReturn()
        assertEquals(null, JsonPath.read<String?>(me.response.contentAsString, "$.value.displayTag"), "the key is present and null (the frontend reads string | null)")
        mvc.perform(patch("/api/v1/account/me").header("Authorization", auth).json("""{"displayName":"ADMIN"}"""))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.errors[0].field").value("displayName")).andExpect(jsonPath("$.errors[0].code").value("Reserved"))
    }
}
