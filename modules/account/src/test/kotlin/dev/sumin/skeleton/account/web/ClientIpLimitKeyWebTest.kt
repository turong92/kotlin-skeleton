package dev.sumin.skeleton.account.web

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.account.RecordingMailer
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.accounttest.AccountTestBeans
import dev.sumin.skeleton.accounttest.AccountWebTestApplication
import kotlin.test.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * I4 — IP 한도 키는 `ClientIps….limitKey`(IPv6 는 /64) 다. 한 /64 안의 주소를 돌려 쓰며 한도를 비켜 가지 못한다 (감사 · 캡차는 전체 IP 를 그대로 쓴다).
 * 한도를 2 로 낮추고, 같은 /64 의 서로 다른 세 주소로 부른다 — 세 번째가 429 여야 한다.
 */
@SpringBootTest(
    classes = [AccountWebTestApplication::class],
    properties = [
        "skeleton.web.client-ip.mode=direct",
        "skeleton.account.mail.link-base-url=https://app.example.com",
        "skeleton.account.password.bcrypt-strength=4",
        "skeleton.account.sign-up.per-ip=2",
        "skeleton.account.verification.per-ip=2",
        "skeleton.account.verification.attempts-per-ip=2",
        "skeleton.account.reset.per-ip=2",
        "skeleton.account.login.per-ip=2",
    ],
)
@AutoConfigureMockMvc
@Import(AccountTestBeans::class)
class ClientIpLimitKeyWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mail: RecordingMailer

    private fun post(path: String, ip: String, body: String, auth: String? = null): ResultActions =
        mvc.perform(post(path).with { it.remoteAddr = ip; it }.contentType(MediaType.APPLICATION_JSON).content(body).also { r -> auth?.let { r.header("Authorization", it) } })

    @Test
    fun `sign-up is limited per IPv6 slash-64, not per full address`() {
        var n = 0
        val call = { ip: String -> post("/api/v1/account/sign-up", ip, """{"email":"s64-${n++}-${System.nanoTime()}@example.com","password":"tangerine-42-moon"}""") }
        call("2001:db8:a:1::1").andExpect(status().isAccepted)
        call("2001:db8:a:1::2").andExpect(status().isAccepted)
        call("2001:db8:a:1::3").andExpect(status().isTooManyRequests)
        call("2001:db8:a:2::1").andExpect(status().isAccepted)   // another /64 is another client
    }

    @Test
    fun `resend and code entry are limited per IPv6 slash-64`() {
        val handle = "h".repeat(43)
        post("/api/v1/account/verification/resend", "2001:db8:b:1::1", """{"signUpId":"$handle"}""").andExpect(status().isAccepted)
        post("/api/v1/account/verification/resend", "2001:db8:b:1::2", """{"signUpId":"$handle"}""").andExpect(status().isAccepted)
        post("/api/v1/account/verification/resend", "2001:db8:b:1::3", """{"signUpId":"$handle"}""").andExpect(status().isTooManyRequests)

        post("/api/v1/auth/verify-email", "2001:db8:b:2::1", """{"signUpId":"$handle","code":"000000"}""").andExpect(status().isGone)
        post("/api/v1/auth/verify-email", "2001:db8:b:2::2", """{"signUpId":"$handle","code":"000000"}""").andExpect(status().isGone)
        post("/api/v1/auth/verify-email", "2001:db8:b:2::3", """{"signUpId":"$handle","code":"000000"}""").andExpect(status().isTooManyRequests)
    }

    @Test
    fun `forgot-password and login are limited per IPv6 slash-64`() {
        for (i in 1..2) post("/api/v1/account/password/forgot", "2001:db8:c:1::$i", """{"email":"nobody@example.com"}""").andExpect(status().isAccepted)
        post("/api/v1/account/password/forgot", "2001:db8:c:1::3", """{"email":"nobody@example.com"}""").andExpect(status().isTooManyRequests)

        for (i in 1..2) post("/api/v1/auth/login", "2001:db8:c:2::$i", """{"email":"nobody$i@example.com","password":"tangerine-42-moon"}""").andExpect(status().isUnauthorized)
        post("/api/v1/auth/login", "2001:db8:c:2::3", """{"email":"nobody3@example.com","password":"tangerine-42-moon"}""").andExpect(status().isTooManyRequests)
    }

    @Test
    fun `the email-change code entry is limited per IPv6 slash-64`() {
        val email = "ec${System.nanoTime()}@example.com"
        val id = JsonPath.read<String>(post("/api/v1/account/sign-up", "203.0.113.11", """{"email":"$email","password":"tangerine-42-moon"}""").andReturn().response.contentAsString, "$.value.signUpId")
        val code = mail.of(MailKind.VERIFY_CODE).last().vars.getValue("code")
        val token = JsonPath.read<String>(post("/api/v1/auth/verify-email", "203.0.113.12", """{"signUpId":"$id","code":"$code"}""").andReturn().response.contentAsString, "$.value.accessToken")
        val bearer = "Bearer $token"
        for (i in 1..2) post("/api/v1/account/email/change/confirm", "2001:db8:d:1::$i", """{"code":"000000"}""", bearer).andExpect(status().isGone)
        post("/api/v1/account/email/change/confirm", "2001:db8:d:1::3", """{"code":"000000"}""", bearer).andExpect(status().isTooManyRequests)
    }
}
