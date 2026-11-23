package dev.sumin.skeleton.account.web

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.account.RecordingMailer
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.accounttest.AccountTestBeans
import dev.sumin.skeleton.accounttest.AccountWebTestApplication
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue

private val taggedIp = AtomicInteger()

/** 닉네임 TAGGED + 자동 닉네임 — 응답의 displayTag */
@SpringBootTest(
    classes = [AccountWebTestApplication::class],
    properties = [
        "skeleton.account.mail.link-base-url=https://app.example.com",
        "skeleton.account.password.bcrypt-strength=4",
        "skeleton.account.admin.enabled=true",
        "skeleton.account.bootstrap.admin-email=boss@example.com",
        "skeleton.account.display-name.uniqueness=TAGGED",
        "skeleton.account.display-name.fallback=GENERATED",
    ],
)
@AutoConfigureMockMvc
@Import(AccountTestBeans::class)
class DisplayNameTaggedWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mail: RecordingMailer

    @BeforeEach fun clear() { mail.sent.clear() }

    private fun MockHttpServletRequestBuilder.json(body: String) = contentType(MediaType.APPLICATION_JSON).content(body)

    private fun register(email: String, name: String?): String {
        val r = mvc.perform(
            post("/api/v1/account/sign-up").with { it.remoteAddr = "198.51.100.${150 + taggedIp.getAndIncrement() % 100}"; it }
                .json("""{"email":"$email","password":"tangerine-42-moon"${name?.let { ""","displayName":"$it"""" } ?: ""}}"""),
        ).andExpect(status().isAccepted).andReturn()
        val id = JsonPath.read<String>(r.response.contentAsString, "$.value.signUpId")
        val code = mail.of(MailKind.VERIFY_CODE).last().vars.getValue("code")
        val ok = mvc.perform(post("/api/v1/auth/verify-email").json("""{"signUpId":"$id","code":"$code"}""")).andExpect(status().isOk).andReturn()
        return "Bearer " + JsonPath.read<String>(ok.response.contentAsString, "$.value.accessToken")
    }

    @Test
    fun `me carries a four digit displayTag next to the name, and a rename draws a new one`() {
        val auth = register("t${System.nanoTime()}@example.com", "Same")
        val first = JsonPath.read<String>(mvc.perform(get("/api/v1/account/me").header("Authorization", auth)).andExpect(status().isOk).andExpect(jsonPath("$.value.displayName").value("Same")).andReturn().response.contentAsString, "$.value.displayTag")
        assertTrue(Regex("^[0-9]{4}$").matches(first), first)
        mvc.perform(patch("/api/v1/account/me").header("Authorization", auth).json("""{"displayName":"Other"}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.value.displayName").value("Other")).andExpect(jsonPath("$.value.displayTag").value(org.hamcrest.Matchers.matchesPattern("^[0-9]{4}$")))
    }

    @Test
    fun `an account without a nickname gets a generated one with a tag`() {
        val auth = register("g${System.nanoTime()}@example.com", null)
        mvc.perform(get("/api/v1/account/me").header("Authorization", auth)).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.displayName").value(org.hamcrest.Matchers.matchesPattern("^user-[0-9a-f]{6}$")))
            .andExpect(jsonPath("$.value.displayTag").value(org.hamcrest.Matchers.matchesPattern("^[0-9]{4}$")))
    }

    @Test
    fun `the length rule counts characters not UTF-16 units - forty emoji fit, sixty-one letters do not, on both sign-up and profile update`() {
        val auth = register("e${System.nanoTime()}@example.com", "😀".repeat(40))
        mvc.perform(get("/api/v1/account/me").header("Authorization", auth)).andExpect(status().isOk).andExpect(jsonPath("$.value.displayName").value("😀".repeat(40)))
        mvc.perform(patch("/api/v1/account/me").header("Authorization", auth).json("""{"displayName":"${"x".repeat(61)}"}"""))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.errors[0].field").value("displayName")).andExpect(jsonPath("$.errors[0].code").value("Size"))
        mvc.perform(post("/api/v1/account/sign-up").with { it.remoteAddr = "198.51.100.77"; it }.json("""{"email":"long${System.nanoTime()}@example.com","password":"tangerine-42-moon","displayName":"${"x".repeat(61)}"}"""))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.errors[0].field").value("displayName")).andExpect(jsonPath("$.errors[0].code").value("Size"))
    }

    @Test
    fun `the admin view shows the tag too`() {
        val boss = register("boss@example.com", "Boss")
        val victim = "v${System.nanoTime()}@example.com"
        register(victim, "Vic")
        mvc.perform(get("/api/v1/admin/accounts").param("email", victim).header("Authorization", boss)).andExpect(status().isOk)
            .andExpect(jsonPath("$.values[0].displayName").value("Vic")).andExpect(jsonPath("$.values[0].displayTag").value(org.hamcrest.Matchers.matchesPattern("^[0-9]{4}$")))
    }
}
