package dev.sumin.skeleton.auth.magiclink

import dev.sumin.skeleton.account.AccountPatch
import dev.sumin.skeleton.account.AccountRepository
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.magiclinktest.MagicLinkTestApplication
import dev.sumin.skeleton.magiclinktest.MagicLinkTestBeans
import dev.sumin.skeleton.magiclinktest.Mails
import dev.sumin.skeleton.magiclinktest.MutableTime
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

/** 매직 링크는 계정 이메일로만 간다 — 연락 이메일로 요청해도 아무것도 안 나가고 계정 이메일 요청도 연락 이메일에는 0통 (docs/accounts.md "연락 이메일 — 어디로 무엇이 가나", `MailRoutingTest` 의 짝) */
@SpringBootTest(
    classes = [MagicLinkTestApplication::class],
    properties = [
        "skeleton.account.mail.link-base-url=https://app.example.com", "skeleton.account.password.bcrypt-strength=4",
        "skeleton.auth-magic-link.sign-up=true", "skeleton.account.contact-email.enabled=true",
    ],
)
@AutoConfigureMockMvc
@Import(MagicLinkTestBeans::class)
class MagicLinkContactEmailTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mails: Mails
    @Autowired lateinit var time: MutableTime
    @Autowired lateinit var accounts: AccountRepository

    @BeforeEach fun clear() { mails.sent.clear() }

    private fun request(email: String) = mvc.perform(post("/api/v1/auth/magic-link/request").contentType(MediaType.APPLICATION_JSON).content("""{"email":"$email"}""")).andExpect(status().isAccepted)

    @Test
    fun `a magic link goes to the account address - the contact address is just another unclaimed address, it never reaches the account`() {
        val email = "ml-c${System.nanoTime()}@example.com"
        val contact = "contact-${System.nanoTime()}@example.com"
        request(email)
        val token = mails.tokenOf(mails.sent.last { it.kind == MailKind.MAGIC_LINK })
        val id = com.jayway.jsonpath.JsonPath.read<String>(
            mvc.perform(post("/api/v1/auth/magic-link/redeem").contentType(MediaType.APPLICATION_JSON).content("""{"token":"$token"}""")).andExpect(status().isOk).andReturn().response.contentAsString,
            "$.value.principal.accountId",
        )
        accounts.update(id, AccountPatch(contactEmail = contact), time.now())

        mails.sent.clear()
        request(email)
        assertEquals(listOf(email), mails.sent.map { it.to }, "one mail, to the account address")
        mails.sent.clear()
        request(contact)
        // sign-up is on in this app: the contact address is a free address, so it gets a plain NEW-sign-up link (not the account's) and the account address gets nothing
        assertTrue(mails.sent.all { it.to == contact }, "sent: ${mails.sent.map { it.to }}")
        val newAccount = com.jayway.jsonpath.JsonPath.read<String>(
            mvc.perform(post("/api/v1/auth/magic-link/redeem").contentType(MediaType.APPLICATION_JSON).content("""{"token":"${mails.tokenOf(mails.sent.single())}"}""")).andExpect(status().isOk).andReturn().response.contentAsString,
            "$.value.principal.accountId",
        )
        assertTrue(newAccount != id, "the link opened a different (new) account, never the account that lists this address as its contact address")
    }
}
