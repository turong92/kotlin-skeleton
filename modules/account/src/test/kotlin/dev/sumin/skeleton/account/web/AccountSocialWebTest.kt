package dev.sumin.skeleton.account.web

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.account.RecordingMailer
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.accounttest.AccountTestBeans
import dev.sumin.skeleton.accounttest.AccountWebTestApplication
import dev.sumin.skeleton.accounttest.FakeSocialBeans
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(
    classes = [AccountWebTestApplication::class],
    properties = [
        "skeleton.account.mail.link-base-url=https://app.example.com",
        "skeleton.account.password.bcrypt-strength=4",
        "skeleton.account.social.sign-up=true",
        "skeleton.auth-social.providers.fakeidp.enabled=true",
    ],
)
@AutoConfigureMockMvc
@Import(AccountTestBeans::class, FakeSocialBeans::class)
class AccountSocialWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mail: RecordingMailer

    private fun json(code: String) = """{"authorizationCode":"$code"}"""
    private fun jsonWithPassword(code: String) = """{"authorizationCode":"$code","currentPassword":"tangerine-42-moon"}"""
    private fun social(code: String) = mvc.perform(post("/api/v1/auth/social/fakeidp/login").contentType(MediaType.APPLICATION_JSON).content(json(code)))
    private fun bearer(r: MvcResult) = "Bearer " + JsonPath.read<String>(r.response.contentAsString, "$.value.accessToken")

    private fun passwordAccount(email: String): MvcResult {
        mail.sent.clear()
        mvc.perform(post("/api/v1/account/sign-up").contentType(MediaType.APPLICATION_JSON).content("""{"email":"$email","password":"tangerine-42-moon"}""")).andExpect(status().isAccepted)
        mvc.perform(post("/api/v1/auth/verify-email").contentType(MediaType.APPLICATION_JSON).content("""{"token":"${mail.tokenOf(mail.of(MailKind.VERIFY_EMAIL).last())}"}""")).andExpect(status().isOk)
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""{"email":"$email","password":"tangerine-42-moon"}""")).andExpect(status().isOk).andReturn()
    }

    @Test
    fun `a first social sign-in creates an account and the next one finds it`() {
        val first = social("c-new").andExpect(status().isOk).andReturn()
        val second = social("c-new").andExpect(status().isOk).andReturn()
        assertEquals(JsonPath.read<String>(first.response.contentAsString, "$.value.principal.accountId"), JsonPath.read<String>(second.response.contentAsString, "$.value.principal.accountId"))
        assertEquals("social-new@example.com", JsonPath.read<String>(first.response.contentAsString, "$.value.principal.email"))
    }

    @Test
    fun `a provider email matching an existing account is a 409 and nothing is linked`() {
        passwordAccount("taken@example.com")
        social("c-conflict").andExpect(status().isConflict).andExpect(jsonPath("$.code").value("ACCOUNT.SOCIAL_EMAIL_CONFLICT"))
    }

    @Test
    fun `an unverified provider email neither conflicts nor reserves the address`() {
        passwordAccount("victim@example.com")
        social("c-unverified").andExpect(status().isOk).andExpect(jsonPath("$.value.principal.email").value(""))
    }

    @Test
    fun `a wrong authorization code is the existing 401`() {
        social("garbage").andExpect(status().isUnauthorized)
    }

    @Test
    fun `a signed-in user links a provider, sees it listed, and a second account cannot take it`() {
        val me = passwordAccount("linker@example.com")
        val auth = bearer(me)
        mvc.perform(post("/api/v1/account/identities/social/fakeidp").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content(jsonWithPassword("c-link")))
            .andExpect(status().isCreated).andExpect(jsonPath("$.value.method").value("fakeidp"))
        mvc.perform(post("/api/v1/account/identities/social/fakeidp").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content(jsonWithPassword("c-link")))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("ACCOUNT.IDENTITY_EXISTS"))
        mvc.perform(get("/api/v1/account/identities").header("Authorization", auth)).andExpect(status().isOk).andExpect(jsonPath("$.values.length()").value(2))

        val other = bearer(passwordAccount("other-user@example.com"))
        mvc.perform(post("/api/v1/account/identities/social/fakeidp").header("Authorization", other).contentType(MediaType.APPLICATION_JSON).content(jsonWithPassword("c-link")))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("ACCOUNT.IDENTITY_TAKEN"))

        // social sign-in now reaches the linked account, and unlinking leaves the password
        social("c-link").andExpect(status().isOk).andExpect(jsonPath("$.value.principal.email").value("linker@example.com"))
        val id = JsonPath.read<List<String>>(mvc.perform(get("/api/v1/account/identities").header("Authorization", auth)).andReturn().response.contentAsString, "$.values[?(@.method=='fakeidp')].id").first()
        mvc.perform(delete("/api/v1/account/identities/$id").header("Authorization", auth)).andExpect(status().isNoContent)
        social("c-link").andExpect(status().isOk)   // c-link's email now belongs to nobody and sign-up is open: a fresh account, not the old one
    }

    @Test
    fun `linking an unknown provider is the provider-not-found error and linking needs a login`() {
        val auth = bearer(passwordAccount("nolink@example.com"))
        mvc.perform(post("/api/v1/account/identities/social/myspace").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content(jsonWithPassword("c-link")))
            .andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("AUTH_SOCIAL.PROVIDER_NOT_FOUND"))
        mvc.perform(post("/api/v1/account/identities/social/fakeidp").contentType(MediaType.APPLICATION_JSON).content(json("c-link"))).andExpect(status().isUnauthorized)
    }

    @Test
    fun `linking needs the current password - a code obtained by someone else cannot be attached to my account (login CSRF)`() {
        val auth = bearer(passwordAccount("csrf-victim@example.com"))
        mvc.perform(post("/api/v1/account/identities/social/fakeidp").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content(json("c-link")))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ACCOUNT.CURRENT_PASSWORD_INVALID"))
        mvc.perform(post("/api/v1/account/identities/social/fakeidp").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content("""{"authorizationCode":"c-link","currentPassword":"not-the-password-1"}"""))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ACCOUNT.CURRENT_PASSWORD_INVALID"))
        mvc.perform(get("/api/v1/account/identities").header("Authorization", auth)).andExpect(jsonPath("$.values.length()").value(1))
    }

    @Test
    fun `an account without a password links only after the mailbox confirmation, and the account's address is told`() {
        val auth = bearer(social("c-new").andExpect(status().isOk).andReturn())   // a social-only account (no password)
        mail.sent.clear()
        mvc.perform(post("/api/v1/account/identities/social/fakeidp").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content(json("c-link")))
            .andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("ACCOUNT.REAUTH_REQUIRED"))
        mvc.perform(post("/api/v1/account/reauth/confirmation").header("Authorization", auth)).andExpect(status().isAccepted).andExpect(jsonPath("$.value.status").value("ACCEPTED"))
        val token = mail.tokenOf(mail.of(MailKind.REAUTH_CONFIRM).single())
        mvc.perform(post("/api/v1/account/identities/social/fakeidp").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content("""{"authorizationCode":"c-link","confirmationToken":"garbage-garbage-garbage-garbage"}"""))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ACCOUNT.REAUTH_FAILED"))
        // the confirmation passes (the account already has this provider, so the link itself is the 409 — proof that re-auth was accepted)
        mvc.perform(post("/api/v1/account/identities/social/fakeidp").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content("""{"authorizationCode":"c-link","confirmationToken":"$token"}"""))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("ACCOUNT.IDENTITY_EXISTS"))
    }

    @Test
    fun `a link by a password account mails a notice to the account address`() {
        val auth = bearer(passwordAccount("notice-me@example.com"))
        mail.sent.clear()
        mvc.perform(post("/api/v1/account/identities/social/fakeidp").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content(jsonWithPassword("c-other"))).andExpect(status().isCreated)
        assertEquals("notice-me@example.com", mail.of(MailKind.IDENTITY_LINKED_NOTICE).single().to)
    }
}
