package dev.sumin.skeleton.authz

import dev.sumin.skeleton.authztest.AuthzWebFakes
import dev.sumin.skeleton.authztest.AuthzWebTestApplication
import dev.sumin.skeleton.authztest.RecordingAudit
import dev.sumin.skeleton.authztest.StubHolders
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(
    classes = [AuthzWebTestApplication::class],
    properties = [
        "skeleton.authz.admin.enabled=true",
        "skeleton.authz.roles.ADMIN=*",
        "skeleton.authz.roles.OPS=authz.roles",
        "skeleton.authz.roles.BACKUP=authz.roles",
        "skeleton.authz.roles.CS=payment.read",
        "skeleton.authz.roles.ACCT=account.roles",
    ],
)
@AutoConfigureMockMvc
@Import(AuthzWebFakes::class)
class RoleAdminWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var store: FakeRoleStore
    @Autowired lateinit var audit: RecordingAudit
    @Autowired lateinit var holders: StubHolders

    private val admin = TestingAuthenticationToken("acc_admin", "n/a", "ROLE_ADMIN")
    private val ops = TestingAuthenticationToken("acc_ops", "n/a", "ROLE_OPS")
    private val opsWithBackup = TestingAuthenticationToken("acc_ops2", "n/a", "ROLE_OPS", "ROLE_BACKUP")
    private val acct = TestingAuthenticationToken("acc_acct", "n/a", "ROLE_ACCT")
    private val user = TestingAuthenticationToken("acc_user", "n/a", "ROLE_USER")

    @BeforeEach
    fun reset() {
        store.rows.keys.retainAll(setOf("ADMIN", "OPS", "CS"))
        store.rows["OPS"] = StoredRole("OPS", null, setOf("authz.roles"))
        store.rows["CS"] = StoredRole("CS", "Support", setOf("payment.read"))
        audit.actions.clear()
        holders.counts.clear()
        holders.revoked.clear()
    }

    private fun call(builder: MockHttpServletRequestBuilder, who: TestingAuthenticationToken? = null): ResultActions =
        mvc.perform(if (who != null) builder.principal(who) else builder)

    private fun MockHttpServletRequestBuilder.json(body: String) = contentType(MediaType.APPLICATION_JSON).content(body)

    private fun createRole(who: TestingAuthenticationToken?, name: String, permissions: String) =
        call(post("/api/v1/admin/roles").json("""{"name":"$name","description":"d","permissions":[$permissions]}"""), who)

    private fun editRole(who: TestingAuthenticationToken?, name: String, permissions: String) =
        call(put("/api/v1/admin/roles/$name").json("""{"description":"d","permissions":[$permissions]}"""), who)

    // ---- who may call ----

    @Test
    fun `anonymous is 401 and an operator without authz roles is 403`() {
        call(get("/api/v1/admin/roles")).andExpect(status().isUnauthorized)
        call(get("/api/v1/admin/roles"), user).andExpect(status().isForbidden)
        call(get("/api/v1/admin/permissions"), acct).andExpect(status().isForbidden)
        createRole(acct, "NEWROLE", "").andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("COMMON.FORBIDDEN"))
    }

    @Test
    fun `account roles holder may read the role list but not change it`() {
        call(get("/api/v1/admin/roles"), acct).andExpect(status().isOk).andExpect(jsonPath("$.values[?(@.name=='CS')]").exists())
        call(delete("/api/v1/admin/roles/CS"), acct).andExpect(status().isForbidden)
    }

    @Test
    fun `permission catalog lists declared permissions with their module`() {
        call(get("/api/v1/admin/permissions"), ops).andExpect(status().isOk)
            .andExpect(jsonPath("$.values[?(@.key=='payment.refund')].module").value("payment"))
            .andExpect(jsonPath("$.values[?(@.key=='authz.roles')].module").value("authz"))
    }

    @Test
    fun `role list carries holders and the protected flag`() {
        holders.counts["CS"] = 3
        call(get("/api/v1/admin/roles"), admin).andExpect(status().isOk)
            .andExpect(jsonPath("$.values[?(@.name=='CS')].holders").value(3))
            .andExpect(jsonPath("$.values[?(@.name=='ADMIN')].protected").value(true))
            .andExpect(jsonPath("$.values[?(@.name=='CS')].protected").value(false))
    }

    // ---- full access is fixed ----

    @Test
    fun `the full access role cannot be edited or deleted`() {
        editRole(admin, "ADMIN", "\"payment.read\"").andExpect(status().isConflict).andExpect(jsonPath("$.code").value("AUTHZ.ROLE_PROTECTED"))
        call(delete("/api/v1/admin/roles/ADMIN"), admin).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("AUTHZ.ROLE_PROTECTED"))
        assertEquals(setOf("*"), store.rows["ADMIN"]!!.permissions)
    }

    @Test
    fun `no other role may hold the full access permission`() {
        createRole(admin, "EVERYTHING", "\"*\"").andExpect(status().isConflict).andExpect(jsonPath("$.code").value("AUTHZ.ROLE_PROTECTED"))
        editRole(admin, "CS", "\"*\"").andExpect(status().isConflict).andExpect(jsonPath("$.code").value("AUTHZ.ROLE_PROTECTED"))
        assertEquals(setOf("payment.read"), store.rows["CS"]!!.permissions)
    }

    // ---- no escalation ----

    @Test
    fun `an operator cannot grant a permission they do not hold`() {
        createRole(ops, "REFUNDS", "\"payment.refund\"").andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("AUTHZ.ESCALATION"))
        editRole(ops, "CS", "\"payment.read\",\"payment.refund\"").andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("AUTHZ.ESCALATION"))
        assertTrue("REFUNDS" !in store.rows)
        assertEquals(setOf("payment.read"), store.rows["CS"]!!.permissions)
    }

    @Test
    fun `an operator may grant what they hold and keep what they could not have granted`() {
        createRole(ops, "OPS_COPY", "\"authz.roles\"").andExpect(status().isCreated)
        // CS 가 이미 가진 payment.read 는 ops 에게 없어도 그대로 두면 된다 — 더하는 것만 본다
        editRole(ops, "CS", "\"payment.read\",\"authz.roles\"").andExpect(status().isOk)
    }

    // ---- self lockout ----

    @Test
    fun `an operator cannot remove authz roles from the role that gives it to them`() {
        editRole(ops, "OPS", "").andExpect(status().isConflict).andExpect(jsonPath("$.code").value("AUTHZ.SELF_LOCKOUT"))
        call(delete("/api/v1/admin/roles/OPS"), ops).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("AUTHZ.SELF_LOCKOUT"))
        assertEquals(setOf("authz.roles"), store.rows["OPS"]!!.permissions)
    }

    @Test
    fun `another role that still gives authz roles lets the change through`() {
        editRole(opsWithBackup, "OPS", "").andExpect(status().isOk)
    }

    // ---- delete ----

    @Test
    fun `a role with holders is not deleted`() {
        holders.counts["CS"] = 1
        call(delete("/api/v1/admin/roles/CS"), admin).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("AUTHZ.ROLE_IN_USE"))
        assertTrue("CS" in store.rows)
        holders.counts["CS"] = 0
        assertEquals(0, holders.revoked.size, "a refused delete takes nothing from anyone")
        call(delete("/api/v1/admin/roles/CS"), admin).andExpect(status().isNoContent)
        assertTrue("CS" !in store.rows)
        assertEquals(listOf("CS" to "acc_admin"), holders.revoked, "the delete takes the role from the non-active holders in the same transaction")
    }

    // ---- validation ----

    @Test
    fun `unknown permission 400, duplicate name 409, missing role 404, bad name 400`() {
        createRole(admin, "NEWROLE", "\"payment.fly\"").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("AUTHZ.PERMISSION_UNKNOWN"))
        createRole(admin, "CS", "").andExpect(status().isConflict).andExpect(jsonPath("$.code").value("AUTHZ.ROLE_EXISTS"))
        editRole(admin, "GHOST", "").andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("AUTHZ.ROLE_NOT_FOUND"))
        call(delete("/api/v1/admin/roles/GHOST"), admin).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("AUTHZ.ROLE_NOT_FOUND"))
        createRole(admin, "lower", "").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("COMMON.VALIDATION_FAILED"))
    }

    // ---- the audit hook ----

    @Test
    fun `successful changes are recorded once each and failures not at all`() {
        createRole(admin, "REFUNDS", "\"payment.read\"").andExpect(status().isCreated)
        editRole(admin, "REFUNDS", "\"payment.refund\"").andExpect(status().isOk)
        call(delete("/api/v1/admin/roles/REFUNDS"), admin).andExpect(status().isNoContent)
        assertEquals(listOf("authz.role.created", "authz.role.updated", "authz.role.deleted"), audit.actions.map { it.action })
        assertEquals(mapOf("added" to "payment.refund", "removed" to "payment.read"), audit.actions[1].detail)
        assertEquals("acc_admin", audit.actions[0].actorId)

        audit.actions.clear()
        createRole(ops, "NOPE", "\"payment.refund\"").andExpect(status().isForbidden)
        editRole(admin, "ADMIN", "").andExpect(status().isConflict)
        call(delete("/api/v1/admin/roles/GHOST"), admin).andExpect(status().isNotFound)
        assertEquals(0, audit.actions.size)
    }

    // ---- concurrency ----

    @Test
    fun `two simultaneous creates of one name make exactly one role`() {
        val results = race(8) { createRole(admin, "RACE", "\"payment.read\"").andReturn().response.status }
        assertEquals(1, results.count { it == 201 })
        assertEquals(7, results.count { it == 409 })
        assertEquals(1, audit.actions.count { it.action == "authz.role.created" })
    }

    @Test
    fun `the full access role survives simultaneous edits and deletes`() {
        val results = race(12) { i ->
            when (i % 3) {
                0 -> editRole(admin, "ADMIN", "\"payment.read\"")
                1 -> call(delete("/api/v1/admin/roles/ADMIN"), admin)
                else -> createRole(admin, "SNEAKY$i", "\"*\"")
            }.andReturn().response.status
        }
        assertTrue(results.all { it == 409 }, "every attempt is refused: $results")
        assertEquals(StoredRole("ADMIN", "Full access", setOf("*"), protected = true), store.rows["ADMIN"])
        assertEquals(0, audit.actions.size)
    }

    private fun <T> race(threads: Int, work: (Int) -> T): List<T> {
        val pool = Executors.newFixedThreadPool(threads)
        val barrier = CyclicBarrier(threads)
        try {
            return (0 until threads).map { i -> pool.submit(Callable { barrier.await(); work(i) }) }.map { it.get() }
        } finally {
            pool.shutdownNow()
        }
    }
}

/** 스위치를 켜지 않으면 404 — 스켈레톤 기본은 관리 API 꺼짐 */
@SpringBootTest(classes = [AuthzWebTestApplication::class])
@AutoConfigureMockMvc
@Import(AuthzWebFakes::class)
class RoleAdminSwitchTest {
    @Autowired lateinit var mvc: MockMvc

    @Test
    fun `admin api is 404 until switched on`() {
        val admin = TestingAuthenticationToken("acc_admin", "n/a", "ROLE_ADMIN")
        mvc.perform(get("/api/v1/admin/roles").principal(admin)).andExpect(status().isNotFound)
        mvc.perform(get("/api/v1/admin/permissions").principal(admin)).andExpect(status().isNotFound)
    }
}

/** 자기 잠금은 토큰이 아니라 판정 구현이 보는 역할(`OperatorAccess.rolesOf` — 계정 모듈이면 저장된 역할)로 가린다 */
@SpringBootTest(
    classes = [AuthzWebTestApplication::class],
    properties = ["skeleton.authz.admin.enabled=true", "skeleton.authz.roles.ADMIN=*", "skeleton.authz.roles.OPS=authz.roles", "skeleton.authz.roles.BACKUP=authz.roles"],
)
@AutoConfigureMockMvc
@Import(AuthzWebFakes::class, StoredRolesLockoutTest.StoredRoles::class)
class StoredRolesLockoutTest {
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    class StoredRoles {
        /** 저장소의 역할 — 토큰과 다를 수 있다 */
        val stored = mapOf("acc_stale" to setOf("BACKUP"), "acc_only_ops" to setOf("OPS"))

        @org.springframework.context.annotation.Bean
        fun access(source: dev.sumin.skeleton.common.authz.RolePermissionSource): dev.sumin.skeleton.common.authz.OperatorAccess =
            object : dev.sumin.skeleton.common.authz.OperatorAccess {
                override fun rolesOf(ref: dev.sumin.skeleton.common.authz.OperatorRef) = stored[ref.accountId].orEmpty()
                override fun permissionsOf(ref: dev.sumin.skeleton.common.authz.OperatorRef) = rolesOf(ref).flatMapTo(sortedSetOf()) { source.permissionsOf(it) }
            }
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var store: FakeRoleStore

    private fun edit(who: TestingAuthenticationToken) =
        mvc.perform(put("/api/v1/admin/roles/OPS").principal(who).contentType(MediaType.APPLICATION_JSON).content("""{"description":"d","permissions":[]}"""))

    @BeforeEach
    fun reset() {
        store.rows["OPS"] = StoredRole("OPS", null, setOf("authz.roles"))
    }

    @Test
    fun `a token that still names OPS does not lock out a caller whose stored role is BACKUP`() {
        edit(TestingAuthenticationToken("acc_stale", "n/a", "ROLE_OPS")).andExpect(status().isOk)
    }

    @Test
    fun `a token without roles still locks out a caller whose only stored role is OPS`() {
        edit(TestingAuthenticationToken("acc_only_ops", "n/a", "ROLE_USER")).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("AUTHZ.SELF_LOCKOUT"))
    }
}
