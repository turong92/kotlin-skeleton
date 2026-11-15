package dev.sumin.skeleton.audit.jdbc.web

import dev.sumin.skeleton.audit.jdbc.AuditEntry
import dev.sumin.skeleton.audit.jdbc.AuditLogStore
import dev.sumin.skeleton.audit.jdbc.AuditPermissions
import dev.sumin.skeleton.audit.jdbc.AuditQuery
import dev.sumin.skeleton.common.GlobalExceptionHandler
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorRef
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/** 운영자 조회 — 익명 401 · 권한 없음 403, 필터가 그대로 저장소에 닿고, 커서로 쪽을 넘기며, 고치거나 지우는 길이 없다 */
class AuditAdminWebTest {
    private val at = Instant.parse("2026-11-10T00:00:00Z")
    private val lines = (1L..5L).reversed().map { AuditEntry(it, at.plusSeconds(it), "op_1", "authz.role.created", "role", "R$it", "SUCCESS", "203.0.113.7", "console", mapOf("added" to "payment.read")) }
    private val asked = mutableListOf<AuditQuery>()
    private val store = object : AuditLogStore {
        override fun find(query: AuditQuery): List<AuditEntry> {
            asked += query
            return lines.filter { query.before == null || it.id < query.before }.take(query.limit)
        }

        override fun purgeBefore(cutoff: Instant, limit: Int) = error("the HTTP path never deletes")
    }
    private val permissions = mapOf("auditor" to setOf(AuditPermissions.READ), "support" to setOf("account.read"))
    private val mvc: MockMvc = MockMvcBuilders.standaloneSetup(
        AuditAdminController(store, object : OperatorAccess { override fun permissionsOf(ref: OperatorRef) = permissions[ref.accountId].orEmpty() }),
    ).setControllerAdvice(GlobalExceptionHandler()).build()

    private fun who(name: String) = TestingAuthenticationToken(name, "n", "ROLE_USER").apply { isAuthenticated = true }

    @Test
    fun `anonymous is 401, an operator without audit read is 403, and neither reaches the store`() {
        mvc.perform(get("/api/v1/admin/audit-logs")).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/v1/admin/audit-logs").principal(AnonymousAuthenticationToken("k", "anon", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")))).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/v1/admin/audit-logs").principal(who("support"))).andExpect(status().isForbidden)
        mvc.perform(get("/api/v1/admin/audit-logs").principal(who("nobody"))).andExpect(status().isForbidden)
        assertEquals(emptyList(), asked)
    }

    @Test
    fun `an auditor reads newest first with every column and the filters reach the store`() {
        mvc.perform(
            get("/api/v1/admin/audit-logs").principal(who("auditor"))
                .param("from", "2026-11-09T00:00:00Z").param("to", "2026-11-11T00:00:00Z").param("actorId", "op_1").param("targetType", "role").param("targetId", "R3"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.values[0].id").value(5))
            .andExpect(jsonPath("$.values[0].action").value("authz.role.created"))
            .andExpect(jsonPath("$.values[0].actorId").value("op_1"))
            .andExpect(jsonPath("$.values[0].result").value("SUCCESS"))
            .andExpect(jsonPath("$.values[0].ip").value("203.0.113.7"))
            .andExpect(jsonPath("$.values[0].detail.added").value("payment.read"))
        assertEquals(
            AuditQuery(Instant.parse("2026-11-09T00:00:00Z"), Instant.parse("2026-11-11T00:00:00Z"), "op_1", "role", "R3", null, 51),
            asked.single(),
            "one extra line is asked to learn whether a next page exists",
        )
    }

    @Test
    fun `a page is cut at size and the cursor is the last id on it`() {
        mvc.perform(get("/api/v1/admin/audit-logs").principal(who("auditor")).param("size", "2"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.values.length()").value(2))
            .andExpect(jsonPath("$.cursor.hasNext").value(true))
            .andExpect(jsonPath("$.cursor.nextCursor").value("4"))
        mvc.perform(get("/api/v1/admin/audit-logs").principal(who("auditor")).param("size", "2").param("cursor", "2"))
            .andExpect(jsonPath("$.values.length()").value(1))
            .andExpect(jsonPath("$.cursor.hasNext").value(false))
    }

    @Test
    fun `a backwards period or a size outside 1 to 100 is 400`() {
        mvc.perform(get("/api/v1/admin/audit-logs").principal(who("auditor")).param("from", "2026-11-11T00:00:00Z").param("to", "2026-11-09T00:00:00Z")).andExpect(status().isBadRequest)
        mvc.perform(get("/api/v1/admin/audit-logs").principal(who("auditor")).param("size", "0")).andExpect(status().isBadRequest)
        mvc.perform(get("/api/v1/admin/audit-logs").principal(who("auditor")).param("size", "101")).andExpect(status().isBadRequest)
    }

    @Test
    fun `the controller maps nothing but GET - there is no way to change or delete a line over HTTP`() {
        val mappings = AuditAdminController::class.java.declaredMethods.flatMap { it.annotations.map { a -> a.annotationClass.simpleName } }
            .filter { it != null && it.endsWith("Mapping") }
        assertEquals(listOf("GetMapping"), mappings)
        assertEquals(emptyList(), asked)
    }
}
