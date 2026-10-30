package dev.sumin.skeleton.auth.magiclink

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.auth.account.AuthAccount
import dev.sumin.skeleton.auth.api.SecondFactorChallenge
import dev.sumin.skeleton.auth.api.SecondFactorGate
import dev.sumin.skeleton.magiclinktest.MagicLinkTestApplication
import dev.sumin.skeleton.magiclinktest.MagicLinkTestBeans
import dev.sumin.skeleton.magiclinktest.Mails
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.jupiter.api.AfterEach
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

/** 매직 링크도 두 번째 단계를 건너뛰는 길이 아니다 — 고리가 차면 링크 소비는 403 `AUTH.MFA_REQUIRED` 이고 토큰이 없다 (링크는 한 번 쓴 것으로 소비된다 — 로그인 시도 한 건) */
@SpringBootTest(
    classes = [MagicLinkTestApplication::class],
    properties = [
        "skeleton.account.mail.link-base-url=https://app.example.com",
        "skeleton.account.password.bcrypt-strength=4",
        "skeleton.auth-magic-link.sign-up=true",
    ],
)
@AutoConfigureMockMvc
@Import(MagicLinkTestBeans::class, MagicLinkSecondFactorWebTest.GateBeans::class)
class MagicLinkSecondFactorWebTest {
    class SwitchedGate : SecondFactorGate {
        @Volatile var on = false
        override fun challenge(account: AuthAccount) = if (on) SecondFactorChallenge("mfa-token-ml", listOf("totp"), Instant.parse("2031-01-01T00:05:00Z")) else null
    }

    @TestConfiguration(proxyBeanMethods = false)
    class GateBeans { @Bean fun switchedGate(): SwitchedGate = SwitchedGate() }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mails: Mails
    @Autowired lateinit var gate: SwitchedGate

    @AfterEach fun off() { gate.on = false }

    private fun request(email: String) = mvc.perform(post("/api/v1/auth/magic-link/request").contentType(MediaType.APPLICATION_JSON).content("""{"email":"$email"}"""))
    private fun redeem(token: String) = mvc.perform(post("/api/v1/auth/magic-link/redeem").contentType(MediaType.APPLICATION_JSON).content("""{"token":"$token"}"""))
    private fun lastToken() = mails.tokenOf(mails.sent.last { it.kind == MailKind.MAGIC_LINK })

    @Test
    fun `redeeming a link for an account with a second step is a 403 challenge and no token`() {
        val email = "ml${System.nanoTime()}@example.com"
        request(email).andExpect(status().isAccepted)
        redeem(lastToken()).andExpect(status().isOk)   // first link: the account is created and signed in (gate empty)

        gate.on = true
        request(email).andExpect(status().isAccepted)
        val body = redeem(lastToken()).andExpect(status().isForbidden).andReturn().response.contentAsString
        assertEquals("AUTH.MFA_REQUIRED", JsonPath.read<String>(body, "$.code"))
        assertEquals("mfa-token-ml", JsonPath.read<String>(body, "$.data.mfaToken"))
        assertFalse("accessToken" in body)
    }
}
