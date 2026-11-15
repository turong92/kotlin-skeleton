package dev.sumin.skeleton.account.web

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.account.RecordingMailer
import dev.sumin.skeleton.account.TestAccountMarks
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.accounttest.AccountTestBeans
import dev.sumin.skeleton.accounttest.AccountWebTestApplication
import dev.sumin.skeleton.common.authz.OperatorAction
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

private val markIps = AtomicInteger()

class RecordingAudit : OperatorAuditLog {
    val actions = CopyOnWriteArrayList<OperatorAction>()
    override fun record(action: OperatorAction) { actions += action }
}

@TestConfiguration(proxyBeanMethods = false)
class TestAccountMarkBeans {
    @Bean fun recordingAudit(): OperatorAuditLog = RecordingAudit()
}

/** 시험 계정 표시 — 운영자만 켜고 끄고(권한 없으면 403), 바뀔 때마다 감사 기록이 한 줄 남고, 이미 그 상태면 아무것도 남기지 않는다 */
@SpringBootTest(
    classes = [AccountWebTestApplication::class],
    properties = [
        "skeleton.account.mail.link-base-url=https://app.example.com",
        "skeleton.account.password.bcrypt-strength=4",
        "skeleton.authz.roles.ADMIN=*",
        "skeleton.authz.roles.SUPPORT=account.read",
        "skeleton.account.admin.enabled=true",
        "skeleton.account.bootstrap.admin-email=boss@example.com",
    ],
)
@AutoConfigureMockMvc
@Import(AccountTestBeans::class, TestAccountMarkBeans::class)
class TestAccountMarkWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mail: RecordingMailer
    @Autowired lateinit var audit: OperatorAuditLog
    @Autowired lateinit var marks: TestAccountMarks
    @Autowired lateinit var accounts: dev.sumin.skeleton.account.AccountRepository

    private val json = MediaType.APPLICATION_JSON
    private fun unique() = "t${System.nanoTime()}@example.com"
    private val recorded get() = (audit as RecordingAudit).actions

    @BeforeEach fun clear() { mail.sent.clear(); recorded.clear() }

    private fun register(email: String) {
        val signUp = mvc.perform(
            post("/api/v1/account/sign-up").with { it.remoteAddr = "198.51.100.${1 + markIps.getAndIncrement() % 200}"; it }.contentType(json)
                .content("""{"email":"$email","password":"tangerine-42-moon"}"""),
        ).andExpect(status().isAccepted).andReturn()
        val id = JsonPath.read<String>(signUp.response.contentAsString, "$.value.signUpId")
        mvc.perform(post("/api/v1/auth/verify-email").contentType(json).content("""{"signUpId":"$id","code":"${mail.of(MailKind.VERIFY_CODE).last().vars.getValue("code")}"}""")).andExpect(status().isOk)
    }

    private fun login(email: String) = "Bearer " + JsonPath.read<String>(
        mvc.perform(post("/api/v1/auth/login").with { it.remoteAddr = "203.0.113.${1 + markIps.getAndIncrement() % 200}"; it }.contentType(json).content("""{"username":"$email","password":"tangerine-42-moon"}"""))
            .andExpect(status().isOk).andReturn().response.contentAsString,
        "$.value.accessToken",
    )

    private fun boss(): String {
        if (accounts.findByEmail("boss@example.com") == null) { register("boss@example.com") }
        return login("boss@example.com")
    }

    private fun user(): Pair<String, String> {
        val email = unique()
        register(email)
        return email to accounts.findByEmail(email)!!.id
    }

    private fun mark(auth: String, id: String) = mvc.perform(put("/api/v1/admin/accounts/$id/test-account").header("Authorization", auth))
    private fun unmark(auth: String, id: String) = mvc.perform(delete("/api/v1/admin/accounts/$id/test-account").header("Authorization", auth))

    @Test
    fun `an operator marks and clears a test account and each change is one audit line`() {
        val boss = boss()
        val (_, id) = user()
        val bossId = accounts.findByEmail("boss@example.com")!!.id

        mark(boss, id).andExpect(status().isNoContent)
        assertEquals(bossId, marks.find(id)!!.markedBy)
        val read = mvc.perform(get("/api/v1/admin/accounts/$id/test-account").header("Authorization", boss)).andExpect(status().isOk).andReturn().response.contentAsString
        assertTrue(JsonPath.read<Boolean>(read, "$.value.marked"))
        assertEquals(listOf(OperatorAction(bossId, "account.test-mark.set", "account", id)), recorded.toList())

        mark(boss, id).andExpect(status().isNoContent)   // 이미 표시됨 — 두 번째는 기록을 늘리지 않는다
        assertEquals(1, recorded.size)

        unmark(boss, id).andExpect(status().isNoContent)
        assertNull(marks.find(id))
        assertEquals("account.test-mark.cleared", recorded.last().action)
        unmark(boss, id).andExpect(status().isNoContent)
        assertEquals(2, recorded.size, "clearing a clear account writes nothing")
        val after = mvc.perform(get("/api/v1/admin/accounts/$id/test-account").header("Authorization", boss)).andExpect(status().isOk).andReturn().response.contentAsString
        assertFalse(JsonPath.read<Boolean>(after, "$.value.marked"))
    }

    @Test
    fun `only account manage may change the mark - a reader, a plain user and anonymous cannot, and nothing is recorded`() {
        val boss = boss()
        val (supportEmail, supportId) = user()
        mvc.perform(put("/api/v1/admin/accounts/$supportId/roles/SUPPORT").header("Authorization", boss)).andExpect(status().isNoContent)
        val support = login(supportEmail)
        val (plainEmail, _) = user()
        val (_, target) = user()
        recorded.clear()

        // 읽기 권한만 가진 운영자는 표시를 보기만 한다
        mvc.perform(get("/api/v1/admin/accounts/$target/test-account").header("Authorization", support)).andExpect(status().isOk)
        mark(support, target).andExpect(status().isForbidden)
        unmark(support, target).andExpect(status().isForbidden)
        mark(login(plainEmail), target).andExpect(status().isForbidden)
        mvc.perform(put("/api/v1/admin/accounts/$target/test-account")).andExpect(status().isUnauthorized)
        assertNull(marks.find(target))
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun `an unknown account is 404 and an erased one is 410`() {
        val boss = boss()
        mark(boss, "acc_nobody").andExpect(status().isNotFound)
        val (_, id) = user()
        mvc.perform(post("/api/v1/admin/accounts/$id/suspend").header("Authorization", boss)).andExpect(status().isNoContent)
        mvc.perform(post("/api/v1/admin/accounts/$id/erase").header("Authorization", boss)).andExpect(status().isNoContent)
        mark(boss, id).andExpect(status().isGone)
        assertNotNull(accounts.findById(id))
        assertNull(marks.find(id))
    }
}
