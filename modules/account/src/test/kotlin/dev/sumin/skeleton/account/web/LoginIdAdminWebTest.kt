package dev.sumin.skeleton.account.web

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.account.RecordingEvents
import dev.sumin.skeleton.account.RecordingMailer
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.accounttest.AccountTestBeans
import dev.sumin.skeleton.accounttest.AccountWebTestApplication
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val adminIps = AtomicInteger()

@TestConfiguration(proxyBeanMethods = false)
class LoginIdAdminBeans {
    @Bean fun recordingEvents(): RecordingEvents = RecordingEvents()
}

/** §14 L11 — 관리자 응답의 `loginId`(켠 앱에서만 키) · `?loginId=` 정확히 일치. 아이디 기능과 관리자 API 가 모두 켜진 앱 */
@SpringBootTest(
    classes = [AccountWebTestApplication::class],
    properties = [
        "skeleton.account.mail.link-base-url=https://app.example.com",
        "skeleton.account.password.bcrypt-strength=4",
        "skeleton.account.login-id.enabled=true",
        "skeleton.account.admin.enabled=true",
        "skeleton.account.bootstrap.admin-email=boss@example.com",
    ],
)
@AutoConfigureMockMvc
@Import(AccountTestBeans::class, LoginIdAdminBeans::class)
class LoginIdAdminWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mail: RecordingMailer
    @Autowired lateinit var events: RecordingEvents
    @Autowired lateinit var accounts: dev.sumin.skeleton.account.AccountRepository

    @BeforeEach fun clear() { mail.sent.clear() }

    private val json = MediaType.APPLICATION_JSON
    private fun unique() = "u${System.nanoTime()}@example.com"

    private fun register(email: String, loginId: String) {
        val signUp = mvc.perform(
            post("/api/v1/account/sign-up").with { it.remoteAddr = "198.51.100.${1 + adminIps.getAndIncrement() % 200}"; it }.contentType(json)
                .content("""{"email":"$email","password":"tangerine-42-moon","loginId":"$loginId"}"""),
        ).andExpect(status().isAccepted).andReturn()
        val id = JsonPath.read<String>(signUp.response.contentAsString, "$.value.signUpId")
        mvc.perform(post("/api/v1/auth/verify-email").contentType(json).content("""{"signUpId":"$id","code":"${mail.of(MailKind.VERIFY_CODE).last().vars.getValue("code")}"}""")).andExpect(status().isOk)
    }

    private fun login(email: String) = "Bearer " + JsonPath.read<String>(
        mvc.perform(post("/api/v1/auth/login").with { it.remoteAddr = "203.0.113.${1 + adminIps.getAndIncrement() % 200}"; it }.contentType(json).content("""{"username":"$email","password":"tangerine-42-moon"}"""))
            .andExpect(status().isOk).andReturn().response.contentAsString,
        "$.value.accessToken",
    )

    private fun boss(): String {
        if (accounts.findByEmail("boss@example.com") == null) register("boss@example.com", "the.boss")
        return login("boss@example.com")
    }

    private fun list(auth: String, query: String): String =
        mvc.perform(get("/api/v1/admin/accounts$query").header("Authorization", auth)).andExpect(status().isOk).andReturn().response.contentAsString

    @Test
    fun `L11 - the list and the one-account read carry loginId, and an account without one has the key with null`() {
        val auth = boss()
        val email = unique()
        register(email, "adm.read1")
        val withId = JsonPath.read<Map<String, Any?>>(list(auth, "?email=$email"), "$.values[0]")
        assertEquals("adm.read1", withId["loginId"])
        val id = withId["id"] as String
        val one = mvc.perform(get("/api/v1/admin/accounts/$id").header("Authorization", auth)).andExpect(status().isOk).andReturn().response.contentAsString
        assertEquals("adm.read1", JsonPath.read<String>(one, "$.value.loginId"))

        val now = java.time.Instant.now()
        val noId = "adm_none_${System.nanoTime()}"
        accounts.insert(dev.sumin.skeleton.account.Account(noId, unique(), true, dev.sumin.skeleton.account.AccountStatus.ACTIVE, setOf("USER"), null, null, null, now, now), emptyList())
        val row = accounts.findById(noId)!!
        val body = mvc.perform(get("/api/v1/admin/accounts/${row.id}").header("Authorization", auth)).andExpect(status().isOk).andReturn().response.contentAsString
        assertTrue("loginId" in JsonPath.read<Map<String, Any?>>(body, "$.value").keys, "the key is there")
        assertEquals(null, JsonPath.read<String?>(body, "$.value.loginId"))
    }

    @Test
    fun `L11 - loginId= finds the exact id only - a fragment finds nothing`() {
        val auth = boss()
        register(unique(), "adm.exact.one")
        assertEquals(1, JsonPath.read<List<Any>>(list(auth, "?loginId=adm.exact.one"), "$.values").size)
        assertEquals(1L, JsonPath.read<Number>(list(auth, "?loginId=adm.exact.one"), "$.pagination.totalElements").toLong())
        listOf("adm.exact", "exact.one", "adm", "adm.exact.on", "%25exact%25").forEach {
            assertEquals(0, JsonPath.read<List<Any>>(list(auth, "?loginId=$it"), "$.values").size, "fragment $it")
        }
    }

    @Test
    fun `L11 - the searched id is normalized - capitals and spaces`() {
        val auth = boss()
        register(unique(), "adm.norm.two")
        assertEquals(1, JsonPath.read<List<Any>>(list(auth, "?loginId=ADM.Norm.TWO"), "$.values").size)
        val padded = mvc.perform(get("/api/v1/admin/accounts").param("loginId", "  adm.norm.two ").header("Authorization", auth)).andExpect(status().isOk).andReturn().response.contentAsString
        assertEquals(1, JsonPath.read<List<Any>>(padded, "$.values").size)
    }

    @Test
    fun `L11 - a MODERATOR cannot read the id through the admin API, nor can a normal user or anonymous`() {
        val auth = boss()
        val email = unique()
        register(email, "adm.mod.three")
        val id = accounts.findByEmail(email)!!.id
        mvc.perform(put("/api/v1/admin/accounts/$id/roles/MODERATOR").header("Authorization", auth)).andExpect(status().isNoContent)
        val moderator = login(email)   // a fresh token carries the new role; the admin check re-reads the role on every call anyway
        mvc.perform(get("/api/v1/admin/accounts").param("loginId", "adm.mod.three").header("Authorization", moderator)).andExpect(status().isForbidden)
        mvc.perform(get("/api/v1/admin/accounts/$id").header("Authorization", moderator)).andExpect(status().isForbidden)
        val plain = unique()
        register(plain, "adm.plain.four")
        mvc.perform(get("/api/v1/admin/accounts").param("loginId", "adm.mod.three").header("Authorization", login(plain))).andExpect(status().isForbidden)
        mvc.perform(get("/api/v1/admin/accounts").param("loginId", "adm.mod.three")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `L11 - searching by id leaves no event and no log detail - the searched value is nowhere in the recorded events`() {
        val auth = boss()
        register(unique(), "zq7.probe-adm")
        val before = events.all.size
        list(auth, "?loginId=zq7.probe-adm")
        list(auth, "?loginId=ZQ7.PROBE")
        assertEquals(before, events.all.size, "a search publishes nothing")
        assertFalse(events.all.any { e -> e.detail.values.any { "zq7.probe" in it.lowercase() } })
    }
}
