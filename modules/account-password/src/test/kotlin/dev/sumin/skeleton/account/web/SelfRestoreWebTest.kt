package dev.sumin.skeleton.account.web

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.account.RecordingMailer
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.accounttest.AccountTestBeans
import dev.sumin.skeleton.accounttest.AccountWebTestApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(
    classes = [AccountWebTestApplication::class],
    properties = [
        "skeleton.account.mail.link-base-url=https://app.example.com",
        "skeleton.account.password.bcrypt-strength=4",
        "skeleton.account.deletion.self-restore=true",
    ],
)
@AutoConfigureMockMvc
@Import(AccountTestBeans::class)
class SelfRestoreWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mail: RecordingMailer

    @BeforeEach fun clear() { mail.sent.clear() }

    private fun post(url: String, body: String, auth: String? = null, ip: String = "203.0.113.${(1..250).random()}") =
        mvc.perform(post(url).with { it.remoteAddr = ip; it }.contentType(MediaType.APPLICATION_JSON).content(body).also { b -> auth?.let { b.header("Authorization", it) } }.also { b -> if (url.endsWith("/account/delete")) b.header("Idempotency-Key", java.util.UUID.randomUUID().toString()) })

    private fun registered(): String {
        val email = "u${System.nanoTime()}@example.com"
        val id = JsonPath.read<String>(post("/api/v1/account/sign-up", """{"email":"$email","password":"tangerine-42-moon"}""", ip = "198.51.100.${(1..250).random()}").andExpect(status().isAccepted).andReturn().response.contentAsString, "$.value.signUpId")
        post("/api/v1/auth/verify-email", """{"signUpId":"$id","code":"${mail.of(MailKind.VERIFY_CODE).last().vars.getValue("code")}"}""").andExpect(status().isOk)
        return email
    }

    private fun login(email: String, password: String = "tangerine-42-moon"): MvcResult = post("/api/v1/auth/login", """{"email":"$email","password":"$password"}""").andReturn()

    @Test
    fun `a signed-in owner of an account in its grace period gets the pending state, cancels with the token and signs in normally`() {
        val email = registered()
        val auth = "Bearer " + JsonPath.read<String>(login(email).response.contentAsString, "$.value.accessToken")
        post("/api/v1/account/delete", """{"currentPassword":"tangerine-42-moon"}""", auth).andExpect(status().isAccepted)

        assertEquals(401, login(email, "wrong-password-1").response.status, "a wrong password is the same as for any account")
        val pending = login(email)
        assertEquals(403, pending.response.status)
        val body = pending.response.contentAsString
        assertEquals("AUTH.ACCOUNT_DELETION_PENDING", JsonPath.read<String>(body, "$.code"))
        assertTrue(JsonPath.read<String>(body, "$.data.purgeAfter").isNotBlank())
        assertTrue(JsonPath.read<String>(body, "$.data.restoreToken").length >= 20)
        assertTrue(!body.contains("accessToken"), "no session is issued")
        val token = JsonPath.read<String>(body, "$.data.restoreToken")

        post("/api/v1/account/delete/cancel", """{"restoreToken":"$token"}""").andExpect(status().isOk).andExpect(jsonPath("$.value.accessToken").isString)
        assertEquals(1, mail.of(MailKind.DELETION_CANCELLED).size)
        assertEquals(200, login(email).response.status)
        post("/api/v1/account/delete/cancel", """{"restoreToken":"$token"}""").andExpect(status().isGone).andExpect(jsonPath("$.code").value("ACCOUNT.TOKEN_INVALID"))
    }

    @Test
    fun `the cancel endpoint is public, validates its body and refuses a made-up token`() {
        post("/api/v1/account/delete/cancel", "{}").andExpect(status().isBadRequest)
        post("/api/v1/account/delete/cancel", """{"restoreToken":"definitely-not-a-real-token-123"}""").andExpect(status().isGone)
    }
}
