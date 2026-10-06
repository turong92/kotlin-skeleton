package dev.sumin.skeleton.account.web

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.account.Account
import dev.sumin.skeleton.account.AccountRepository
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.account.Identity
import dev.sumin.skeleton.account.RecordingEvents
import dev.sumin.skeleton.account.RecordingMailer
import dev.sumin.skeleton.account.events.AccountEventType
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.account.password.PasswordHasher
import dev.sumin.skeleton.accounttest.AccountTestBeans
import dev.sumin.skeleton.accounttest.AccountWebTestApplication
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@TestConfiguration(proxyBeanMethods = false)
class RecordingEventsBean {
    @Bean fun recordingEvents(): RecordingEvents = RecordingEvents()
}

/** `LOGIN_SUCCESS` · `lastLoginAt` 은 토큰 발급이 **성공한 뒤에만** 남는다 — 막힌(정지된) 레거시 미확인 계정이 코드로 이어받아도 성공 로그인으로 기록되지 않는다 */
@SpringBootTest(
    classes = [AccountWebTestApplication::class],
    properties = ["skeleton.account.mail.link-base-url=https://app.example.com", "skeleton.account.password.bcrypt-strength=4"],
)
@AutoConfigureMockMvc
@Import(AccountTestBeans::class, RecordingEventsBean::class)
class VerifyEmailSignInRecordingWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mail: RecordingMailer
    @Autowired lateinit var accounts: AccountRepository
    @Autowired lateinit var events: RecordingEvents
    @Autowired lateinit var hasher: PasswordHasher

    private fun post(path: String, body: String, ip: String) = mvc.perform(post(path).with { it.remoteAddr = ip; it }.contentType(MediaType.APPLICATION_JSON).content(body))

    private fun startAttempt(email: String, ip: String): Pair<String, String> {
        val r = post("/api/v1/account/sign-up", """{"email":"$email","password":"tangerine-42-moon"}""", ip).andExpect(status().isAccepted).andReturn()
        return JsonPath.read<String>(r.response.contentAsString, "$.value.signUpId") to mail.of(MailKind.VERIFY_CODE).last { it.to == email }.vars.getValue("code")
    }

    @Test
    fun `a suspended legacy account that takes over its address with the code is refused and NOT recorded as a successful login`() {
        val email = "legacy${System.nanoTime()}@example.com"
        val now = Instant.now()
        val accountId = "acc_leg_${System.nanoTime()}"
        accounts.insert(
            Account(accountId, email, false, AccountStatus.SUSPENDED, setOf("USER"), null, null, null, now, now),
            listOf(Identity("idn_$accountId", accountId, "password", email, false, secret = hasher.hash("old-pass-123456"), createdAt = now)),
        )
        val (id, code) = startAttempt(email, "198.51.100.40")
        events.all.clear()

        post("/api/v1/auth/verify-email", """{"signUpId":"$id","code":"$code"}""", "198.51.100.41").andExpect(status().isForbidden)

        assertNull(accounts.findByEmail(email)!!.lastLoginAt, "a refused sign-in leaves no lastLoginAt")
        assertTrue(AccountEventType.LOGIN_SUCCESS !in events.types(), "and no LOGIN_SUCCESS: ${events.types()}")
    }

    @Test
    fun `a normal verify records the login after the tokens were issued`() {
        val email = "ok${System.nanoTime()}@example.com"
        val (id, code) = startAttempt(email, "198.51.100.42")
        events.all.clear()
        post("/api/v1/auth/verify-email", """{"signUpId":"$id","code":"$code"}""", "198.51.100.43").andExpect(status().isOk)
        assertNotNull(accounts.findByEmail(email)!!.lastLoginAt)
        assertEquals(1, events.all.count { it.type == AccountEventType.LOGIN_SUCCESS })
    }
}
