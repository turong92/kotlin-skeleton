package dev.sumin.skeleton.account.web

import dev.sumin.skeleton.account.Account
import dev.sumin.skeleton.account.AccountRepository
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.account.Identity
import dev.sumin.skeleton.accounttest.AccountTestBeans
import dev.sumin.skeleton.accounttest.AccountWebTestApplication
import dev.sumin.skeleton.auth.account.AccountIdentifier
import dev.sumin.skeleton.auth.account.AuthAccountRepository
import dev.sumin.skeleton.auth.api.AuthTokenResponseFactory
import java.time.Instant
import kotlin.test.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** 계정 HTTP 를 끄고(`skeleton.account.http.enabled=false`, 앱이 자기 컨트롤러를 둘 때) 이메일을 필수로 켜면 — 403 은 컨트롤러와 무관하게 살아 있다 */
@SpringBootTest(
    classes = [AccountWebTestApplication::class],
    properties = ["skeleton.account.password.bcrypt-strength=4", "skeleton.account.email.required=true", "skeleton.account.http.enabled=false"],
)
@AutoConfigureMockMvc
@Import(AccountTestBeans::class, ForeignModuleBeans::class)
class EmailRequiredHttpDisabledWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var accounts: AccountRepository
    @Autowired lateinit var authAccounts: AuthAccountRepository
    @Autowired lateinit var tokens: AuthTokenResponseFactory

    private fun addressLess(id: String): String {
        val now = Instant.now()
        accounts.insert(Account(id, null, false, AccountStatus.ACTIVE, setOf("USER"), "K", null, null, now, now), listOf(Identity("idn_$id", id, "kakao", "kakao-$id", true, createdAt = now)))
        return "Bearer " + tokens.issue(authAccounts.findBy(AccountIdentifier(accountId = id))!!).accessToken
    }

    @Test
    fun `the account controllers are off but an account without an address is still stopped on the app's own endpoints`() {
        val auth = addressLess("acc_nohttp")
        mvc.perform(get("/api/v1/account/me").header("Authorization", auth)).andExpect(status().isNotFound)   // really off
        mvc.perform(get("/api/v1/things").header("Authorization", auth)).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("ACCOUNT.EMAIL_REQUIRED"))
    }
}
