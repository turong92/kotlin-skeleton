package dev.sumin.skeleton.auth.mfa

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.mfatest.FrozenTime
import dev.sumin.skeleton.mfatest.Mails
import dev.sumin.skeleton.mfatest.MfaTestApplication
import dev.sumin.skeleton.mfatest.MfaTestBeans
import kotlin.test.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

private const val PASSWORD = "tangerine-42-moon"

/** 앱이 2단계를 필수로 정한 계정(`required-for: ALL`)은 상태에 `required` 가 서고 끌 수 없다(409). 재등록은 된다. 필수 문(인터셉터)은 다음 조각 */
@SpringBootTest(
    classes = [MfaTestApplication::class],
    properties = [
        "skeleton.account.mail.link-base-url=https://app.example.com",
        "skeleton.account.password.bcrypt-strength=4",
        "skeleton.auth-mfa.required-for=ALL",
        "skeleton.crypto.primary-key-id=local",
        "skeleton.crypto.keys.local=BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc=",
    ],
)
@AutoConfigureMockMvc
@Import(MfaTestBeans::class)
class MfaRequiredByPolicyWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mails: Mails
    @Autowired lateinit var time: FrozenTime

    @Test
    fun `a required account sees required in its state and cannot turn it off, but can enrol again`() {
        val email = "r${System.nanoTime()}@example.com"
        val signUp = mvc.perform(post("/api/v1/account/sign-up").with { it.remoteAddr = "198.51.100.77"; it }.contentType(MediaType.APPLICATION_JSON).content("""{"email":"$email","password":"$PASSWORD"}""")).andExpect(status().isAccepted).andReturn()
        val signUpId = JsonPath.read<String>(signUp.response.contentAsString, "$.value.signUpId")
        val done = mvc.perform(post("/api/v1/auth/verify-email").contentType(MediaType.APPLICATION_JSON).content("""{"signUpId":"$signUpId","code":"${mails.lastCode()}"}""")).andReturn().response.contentAsString
        val bearer = "Bearer " + JsonPath.read<String>(done, "$.value.accessToken")
        fun call(path: String, body: String): ResultActions =
            mvc.perform(post("/api/v1/auth/mfa$path").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content(body))

        mvc.perform(get("/api/v1/auth/mfa").header("Authorization", bearer)).andExpect(jsonPath("$.value.required").value(true))
        val started = call("/totp/enrollment", """{"currentPassword":"$PASSWORD"}""").andExpect(status().isOk).andReturn().response.contentAsString
        val secret = Base32.decode(JsonPath.read<String>(started, "$.value.secret"))
        call("/totp/enrollment/confirm", """{"code":"${Totp.code(secret, Totp.stepOf(time.now()) - 1)}"}""").andExpect(status().isOk)

        call("/disable", """{"currentPassword":"$PASSWORD","code":"${Totp.code(secret, Totp.stepOf(time.now()))}"}""").andExpect(status().isConflict).andExpect(jsonPath("$.code").value("MFA.REQUIRED_BY_POLICY"))
        call("/totp/enrollment", """{"currentPassword":"$PASSWORD"}""").andExpect(status().isOk)
    }
}
