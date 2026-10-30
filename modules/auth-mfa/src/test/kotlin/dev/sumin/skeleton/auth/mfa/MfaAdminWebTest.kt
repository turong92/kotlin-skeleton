package dev.sumin.skeleton.auth.mfa

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.account.Account
import dev.sumin.skeleton.account.AccountRepository
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.account.events.AccountEventType
import dev.sumin.skeleton.mfatest.Audits
import dev.sumin.skeleton.mfatest.Events
import dev.sumin.skeleton.mfatest.FakeSessions
import dev.sumin.skeleton.mfatest.FrozenTime
import dev.sumin.skeleton.mfatest.Mails
import dev.sumin.skeleton.mfatest.MfaTestApplication
import dev.sumin.skeleton.mfatest.MfaTestBeans
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

private val seq = AtomicInteger()
private const val PASSWORD = "tangerine-42-moon"
private const val BASE = "/api/v1/admin/mfa/accounts"
private val T0 = Instant.parse("2026-10-31T00:00:00Z")

/** 운영자 해제(`admin.enabled=true`) - 권한 · 자기 · 상승 · 두 표 지움 · 대상 세션 전부 · 메일 · 이벤트 · 운영 기록(대상 id 만) · 해제 뒤 코드 없이 로그인 */
@SpringBootTest(
    classes = [MfaTestApplication::class],
    properties = [
        "skeleton.account.mail.link-base-url=https://app.example.com",
        "skeleton.account.password.bcrypt-strength=4",
        "skeleton.auth-mfa.admin.enabled=true",
        "skeleton.crypto.primary-key-id=local",
        "skeleton.crypto.keys.local=BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc=",
        "skeleton.authz.roles.ADMIN=*",
        "skeleton.authz.roles.MFAOPS=mfa.reset",
        "skeleton.authz.roles.MFASUP=mfa.reset,account.read",
        "skeleton.authz.roles.READER=account.read",
    ],
)
@AutoConfigureMockMvc
@Import(MfaTestBeans::class)
class MfaAdminWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var accounts: AccountRepository
    @Autowired lateinit var store: FakeMfaStore
    @Autowired lateinit var mails: Mails
    @Autowired lateinit var events: Events
    @Autowired lateinit var audits: Audits
    @Autowired lateinit var sessions: FakeSessions
    @Autowired lateinit var time: FrozenTime

    private class Target(val id: String, val email: String)

    private fun operator(vararg roles: String): TestingAuthenticationToken {
        val id = "acc_op_${seq.incrementAndGet()}_${System.nanoTime()}"
        check(accounts.insert(Account(id, "$id@example.com", true, AccountStatus.ACTIVE, roles.toSet(), null, null, null, T0, T0), emptyList()))
        return TestingAuthenticationToken(id, "n/a", *roles.map { "ROLE_$it" }.toTypedArray())
    }

    /** 2단계를 켠 계정 - 가입 -> 등록 -> 확인 */
    private fun enrolled(): Target {
        val email = "t${System.nanoTime()}@example.com"
        val sign = mvc.perform(post("/api/v1/account/sign-up").with { it.remoteAddr = "198.51.100.${10 + seq.incrementAndGet() % 100}"; it }.contentType(MediaType.APPLICATION_JSON).content("""{"email":"$email","password":"$PASSWORD"}""")).andReturn()
        val done = mvc.perform(post("/api/v1/auth/verify-email").contentType(MediaType.APPLICATION_JSON).content("""{"signUpId":"${JsonPath.read<String>(sign.response.contentAsString, "$.value.signUpId")}","code":"${mails.lastCode()}"}""")).andReturn().response.contentAsString
        val bearer = "Bearer " + JsonPath.read<String>(done, "$.value.accessToken")
        val started = mvc.perform(post("/api/v1/auth/mfa/totp/enrollment").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content("""{"currentPassword":"$PASSWORD"}""")).andReturn().response.contentAsString
        val secret = Base32.decode(JsonPath.read<String>(started, "$.value.secret"))
        mvc.perform(post("/api/v1/auth/mfa/totp/enrollment/confirm").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content("""{"code":"${Totp.code(secret, Totp.stepOf(time.now()) - 1)}"}"""))
            .andExpect(status().isOk)
        return Target(JsonPath.read(done, "$.value.principal.accountId"), email)
    }

    private fun reset(id: String, who: TestingAuthenticationToken, body: String = """{"reason":"verified by video call"}"""): ResultActions =
        mvc.perform(post("$BASE/$id/reset").with(authentication(who)).contentType(MediaType.APPLICATION_JSON).content(body))

    @Test
    fun `reset needs mfa reset, a reason and a real target - and the status view needs account read`() {
        val target = enrolled()

        reset(target.id, operator("READER")).andExpect(status().isForbidden)
        mvc.perform(post("$BASE/${target.id}/reset").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized)
        reset(target.id, operator("MFAOPS"), "{}").andExpect(status().isBadRequest)
        reset(target.id, operator("MFAOPS"), """{"reason":"  "}""").andExpect(status().isBadRequest)
        reset("acc_missing", operator("MFAOPS")).andExpect(status().isNotFound)
        assertEquals(true, store.find(target.id)?.enabled)

        mvc.perform(get("$BASE/${target.id}").with(authentication(operator("MFAOPS")))).andExpect(status().isForbidden)
        mvc.perform(get("$BASE/${target.id}").with(authentication(operator("READER")))).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.enabled").value(true)).andExpect(jsonPath("$.value.recoveryCodesRemaining").value(10))
    }

    @Test
    fun `a reset wipes both tables, closes every session, mails the owner, leaves an event and an audit line with the target id only, and the owner signs in without a code`() {
        val target = enrolled()
        val op = operator("MFASUP")
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""{"email":"${target.email}","password":"$PASSWORD"}""")).andExpect(status().isForbidden)

        reset(target.id, op).andExpect(status().isNoContent)

        assertNull(store.find(target.id))
        assertEquals(0, store.recoveryCodesRemaining(target.id))
        assertEquals(target.id to null, sessions.revoked.last { it.first == target.id })
        assertEquals(1, mails.notices(target.email, "reset").size)
        assertEquals(op.name, events.of(AccountEventType.MFA_RESET_BY_ADMIN, target.id).single().detail["by"])
        val audit = audits.all.single { it.action == "mfa.credential.reset" }
        assertEquals(op.name, audit.actorId)
        assertEquals(target.id, audit.targetId)
        assertEquals(emptyMap(), audit.detail)
        assertFalse("video call" in audit.toString() + events.all.toString(), "the reason is not copied into records")

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""{"email":"${target.email}","password":"$PASSWORD"}""")).andExpect(status().isOk)
    }

    @Test
    fun `an operator cannot reset their own credential, and cannot reset an account above their own permissions`() {
        val op = operator("MFAOPS")
        reset(op.name, op).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("MFA.SELF_RESET"))

        val boss = operator("ADMIN")
        store.startEnrollment(boss.name, "aes-gcm:v1:x", time.now().plusSeconds(600), time.now())
        reset(boss.name, op).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("MFA.ESCALATION"))
        assertEquals(0, audits.all.count { it.targetId == boss.name })
    }

    @Test
    fun `resetting an account that has no second factor is a conflict and records nothing`() {
        val plain = operator("USER")

        reset(plain.name, operator("MFAOPS")).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("MFA.NOT_ENABLED"))
        assertEquals(0, audits.all.count { it.targetId == plain.name })
    }
}
