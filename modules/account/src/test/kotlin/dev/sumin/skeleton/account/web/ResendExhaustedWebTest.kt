package dev.sumin.skeleton.account.web

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.account.RecordingMailer
import dev.sumin.skeleton.accounttest.AccountTestBeans
import dev.sumin.skeleton.accounttest.AccountWebTestApplication
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.Test
import kotlin.test.assertTrue

/** Contract: when no resend can follow, the 202 carries `"resendAvailableAt": null` (the key is there — the frontend hides the button) */
@SpringBootTest(
    classes = [AccountWebTestApplication::class],
    properties = [
        "skeleton.account.mail.link-base-url=https://app.example.com",
        "skeleton.account.password.bcrypt-strength=4",
        "skeleton.account.verification.resend-cooldown=0s",
        "skeleton.account.verification.max-resends=1",
    ],
)
@AutoConfigureMockMvc
@Import(AccountTestBeans::class)
class ResendExhaustedWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mail: RecordingMailer

    @Test
    fun `the last allowed resend and every later one answer with resendAvailableAt null, key present`() {
        val signUp = mvc.perform(
            post("/api/v1/account/sign-up").with { it.remoteAddr = "198.51.100.31"; it }.contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"x${System.nanoTime()}@example.com","password":"tangerine-42-moon"}"""),
        ).andExpect(status().isAccepted).andExpect(jsonPath("$.value.resendAvailableAt").isString).andReturn()
        val id = JsonPath.read<String>(signUp.response.contentAsString, "$.value.signUpId")
        repeat(2) {
            val r = mvc.perform(post("/api/v1/account/verification/resend").with { it.remoteAddr = "198.51.100.32"; it }.contentType(MediaType.APPLICATION_JSON).content("""{"signUpId":"$id"}"""))
                .andExpect(status().isAccepted).andExpect(jsonPath("$.value.expiresAt").isString).andReturn()
            val body = r.response.contentAsString
            assertTrue(JsonPath.read<Map<String, Any?>>(body, "$.value").containsKey("resendAvailableAt"), "the key is present: $body")
            assertTrue(JsonPath.read<Any?>(body, "$.value.resendAvailableAt") == null)
        }
    }
}
