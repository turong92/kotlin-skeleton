package dev.sumin.skeleton.auth.mfa

import dev.sumin.skeleton.account.Account
import dev.sumin.skeleton.account.AccountRepository
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.mfatest.MfaTestApplication
import dev.sumin.skeleton.mfatest.MfaTestBeans
import kotlin.test.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** `http.enabled=false`(앱이 자기 컨트롤러를 쓴다)여도 `required-for` 가 켜져 있으면 필수 문은 선다 - 설정이 조용히 무시되지 않는다 */
@SpringBootTest(
    classes = [MfaTestApplication::class],
    properties = [
        "skeleton.account.mail.link-base-url=https://app.example.com",
        "skeleton.auth-mfa.http.enabled=false",
        "skeleton.auth-mfa.required-for=ALL",
        "skeleton.crypto.primary-key-id=local",
        "skeleton.crypto.keys.local=BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc=",
    ],
)
@AutoConfigureMockMvc
@Import(MfaTestBeans::class)
class MfaGateWithoutRoutesWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var accounts: AccountRepository

    @Test
    fun `the enrolment door stands although the mfa routes are closed`() {
        val now = java.time.Instant.parse("2026-10-31T00:00:00Z")
        check(accounts.insert(Account("acc_gate_1", "gate1@example.com", true, AccountStatus.ACTIVE, setOf("USER"), null, null, null, now, now), emptyList()))
        val any = TestingAuthenticationToken("acc_gate_1", "n/a", "ROLE_USER")
        mvc.perform(patch("/api/v1/account/me").with(authentication(any)).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("MFA.ENROLLMENT_REQUIRED"))
    }
}
