package dev.sumin.skeleton.account.web

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.accounttest.AccountTestBeans
import dev.sumin.skeleton.accounttest.AccountWebTestApplication
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 기본 한도에서는 재전송 횟수(3)보다 주소별 메일 예산(시간당 3통 — 첫 메일 포함)이 먼저 바닥난다.
 * 그때도 "더 올 것이 없다"는 같다 — 쿨다운이 지났는데 메일이 안 나가면 resendAvailableAt 은 null 이어야 눌러도 아무 일 없는 버튼이 남지 않는다.
 */
@SpringBootTest(
    classes = [AccountWebTestApplication::class],
    properties = [
        "skeleton.account.mail.link-base-url=https://app.example.com",
        "skeleton.account.password.bcrypt-strength=4",
        "skeleton.account.verification.resend-cooldown=0s",
    ],
)
@AutoConfigureMockMvc
@Import(AccountTestBeans::class)
class ResendBudgetSpentWebTest {
    @Autowired lateinit var mvc: MockMvc

    @Test
    fun `once the mail budget for the address is spent a resend answers with resendAvailableAt null`() {
        val signUp = mvc.perform(
            post("/api/v1/account/sign-up").with { it.remoteAddr = "198.51.100.41"; it }.contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"b${System.nanoTime()}@example.com","password":"tangerine-42-moon"}"""),
        ).andExpect(status().isAccepted).andReturn()
        val id = JsonPath.read<String>(signUp.response.contentAsString, "$.value.signUpId")
        fun resend(): Any? {
            val r = mvc.perform(post("/api/v1/account/verification/resend").with { it.remoteAddr = "198.51.100.42"; it }.contentType(MediaType.APPLICATION_JSON).content("""{"signUpId":"$id"}"""))
                .andExpect(status().isAccepted).andReturn()
            return JsonPath.read<Any?>(r.response.contentAsString, "$.value.resendAvailableAt")
        }
        assertNotNull(resend(), "second mail of three: another resend may follow")
        resend()   // third mail — the budget is now spent
        assertNull(resend(), "no mail went out and none will within this code's life")
    }
}
