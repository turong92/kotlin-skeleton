package dev.sumin.skeleton.auth.magiclink

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.account.AccountPatch
import dev.sumin.skeleton.account.AccountRepository
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.magiclinktest.MagicLinkTestApplication
import dev.sumin.skeleton.magiclinktest.MagicLinkTestBeans
import dev.sumin.skeleton.magiclinktest.Mails
import dev.sumin.skeleton.magiclinktest.MutableTime
import java.time.Duration
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** `deletion.self-restore=true`: 탈퇴 유예 중인 주인에게도 링크가 가고, 링크로 로그인하면 세션 대신 "탈퇴 대기" 상태가 나온다 */
@SpringBootTest(
    classes = [MagicLinkTestApplication::class],
    properties = [
        "skeleton.account.mail.link-base-url=https://app.example.com",
        "skeleton.account.password.bcrypt-strength=4",
        "skeleton.auth-magic-link.sign-up=true",
        "skeleton.account.deletion.self-restore=true",
    ],
)
@AutoConfigureMockMvc
@Import(MagicLinkTestBeans::class)
class MagicLinkSelfRestoreWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mails: Mails
    @Autowired lateinit var time: MutableTime
    @Autowired lateinit var accounts: AccountRepository

    @BeforeEach fun clear() { mails.sent.clear() }

    private fun request(email: String) = mvc.perform(post("/api/v1/auth/magic-link/request").contentType(MediaType.APPLICATION_JSON).content("""{"email":"$email"}"""))
    private fun redeem(token: String) = mvc.perform(post("/api/v1/auth/magic-link/redeem").contentType(MediaType.APPLICATION_JSON).content("""{"token":"$token"}"""))
    private fun lastToken() = mails.tokenOf(mails.sent.last { it.kind == MailKind.MAGIC_LINK })

    @Test
    fun `a magic link for an account in its deletion grace ends in the pending state with a restore token`() {
        val email = "ml${System.nanoTime()}@example.com"
        request(email).andExpect(status().isAccepted)
        val first = redeem(lastToken()).andExpect(status().isOk).andReturn()
        val id = JsonPath.read<String>(first.response.contentAsString, "$.value.principal.accountId")
        accounts.update(id, AccountPatch(status = AccountStatus.DELETED, deletedAt = time.now(), purgeAfter = time.now().plus(Duration.ofDays(30))), time.now())

        mails.sent.clear()
        request(email).andExpect(status().isAccepted)
        val denied = redeem(lastToken()).andExpect(status().isForbidden).andReturn().response.contentAsString
        assertEquals("AUTH.ACCOUNT_DELETION_PENDING", JsonPath.read<String>(denied, "$.code"))
        assertTrue(JsonPath.read<String>(denied, "$.data.restoreToken").length >= 20)
        assertEquals(AccountStatus.DELETED, accounts.findById(id)!!.status)
    }
}
